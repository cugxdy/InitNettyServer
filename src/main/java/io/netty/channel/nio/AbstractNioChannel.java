/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    private static final ClosedChannelException DO_CLOSE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractNioChannel.class, "doClose()");

    // socket类型ServerSocketChannel|SocketChannel
    private final SelectableChannel ch;
    // socket状态标识符
    protected final int readInterestOp;
    // selector轮询就绪套接字描述符
    volatile SelectionKey selectionKey;
    // 禁用输入流
    private volatile boolean inputShutdown;
    // 正在读取数据进行中
    private volatile boolean readPending;

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    // 异步处理连接请求结果
    private ChannelPromise connectPromise;
    // 异步连接超时定时器
    private ScheduledFuture<?> connectTimeoutFuture;
    // 请求服务远程地址
    private SocketAddress requestedRemoteAddress;

    
    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    // 设置SelectableChannel相关参数
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent); // --> AbstractChannel类
        this.ch = ch;
        // 设置accept/read/write状态(read)
        this.readInterestOp = readInterestOp;
        try {
        	// 设置非阻塞模式
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
            	// 关闭SocketChannel通道
                ch.close();
            } catch (IOException e2) {
            	// 抛出异常
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "Failed to close a partially initialized socket.", e2);
                }
            }
            // 失败进入非阻塞模式
            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override// 判断SocketChannel是否打开
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override// 创建unsafe实例
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    // 返回SelectableChannel对象
    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override// 返回线程执行组
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    // 获取selectionKey
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    // 判断是否处于read过程中
    protected boolean isReadPending() {
        return readPending;
    }

    // 设置readPending状态
    protected void setReadPending(boolean readPending) {
        this.readPending = readPending;
    }

    /**
     * Return {@code true} if the input of this {@link Channel} is shutdown
     */
    // 判断是否处于禁止输入的状态中
    protected boolean isInputShutdown() {
        return inputShutdown;
    }

    /**
     * Shutdown the input of this {@link Channel}.
     */
    // 设置输入流为true
    void setInputShutdown() {
        inputShutdown = true;
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

    	// 对网络操作符移除读操作位
        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            if (!key.isValid()) {
                return;
            }
            int interestOps = key.interestOps();
            // 判断当前网络操作位是否存在字段值
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
            	// 仅仅移除读操作位
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override// 获取SocketChannel对象
        public final SelectableChannel ch() {
            return javaChannel();
        }

        @Override// 启动连接操作,连接远程服务器
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
            	// 已经存在一个连接未处理,抛出异常
                if (connectPromise != null) {
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                // 返回false
                boolean wasActive = isActive();
                // 进行连接操作
                if (doConnect(remoteAddress, localAddress)) {
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    connectPromise = promise;
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    // 获取连接请求超时时间
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    
                    // 根据连接超时时间设置定时任务,超时时间到之后触发检验,如果发现连接并没有完成,则关闭连接句柄
                    // 释放资源,设置异常堆栈并发起注册
                    if (connectTimeoutMillis > 0) {
                    	// 创建定时任务并添加至任务队列中
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                            	// 获取connectPromise
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                // 创建连接超时异常
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                // 将connectPromise设置为失败状态
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    // 设置连接结果监听器,如果接收到连接完成通知判断连接是否被取消,如果被取消则关闭连接句柄,释放资源,发起取消注册操作
                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                        	// 执行释放资源
                            if (future.isCancelled()) {
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false);
                                }
                                // 置为空
                                connectPromise = null;
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
            	// 设置为失败状态
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        // 它负责将SocketChannel修改为监听读操作位,用来监听网络的读事件
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
        	// promise不能为空
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            // 返回true
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            // 当用户撤销连接时,trySuccess()将会返回false
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
            	// 触发ChannelActive事件
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
            	// 关闭SocketChannel
                close(voidPromise());
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            // 设置为失败状态
            promise.tryFailure(cause);
            closeIfClosed();
        }

        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            assert eventLoop().inEventLoop();

            try {
                boolean wasActive = isActive();
                // 判断是否完成连接
                doFinishConnect();
                // 触发相关fireChannelActive事件
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
            	// 将connectPromise设置为失败状态
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
            	// 如果连接超时时仍然没有接收到服务端的ACK信息应答信息,则由定时任务关闭客户端连接,将SocketChannel从
            	// Reactor线程的多路复用器上摘除,释放资源。
                if (connectTimeoutFuture != null) {
                    connectTimeoutFuture.cancel(false);
                }
                connectPromise = null;
            }
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
        	// 判断是否正处于flush状态中
            if (isFlushPending()) {
                return;
            }
            super.flush0();
        }

        @Override// 执行flush
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        // 判断网络操作位是否为OP_WRITE状态
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override// 判断是否为NioEventLoop类型
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    // 将socket注册到selector轮询队列中去
    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (;;) {
            try {
            	// 注册至Selector上,仅仅完成注册操作
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                	// 将取消的selectionKey从多路复用器上删除
                    eventLoop().selectNow();
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    @Override// 取消注册
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }

    @Override// 准备处理读操作之前需要设置网络操作位为读
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
    	// 判断是否禁止read操作
        if (inputShutdown) {
            return;
        }

        final SelectionKey selectionKey = this.selectionKey;
        // 检验selectionKey是否可用
        if (!selectionKey.isValid()) {
            return;
        }

        readPending = true;
        // 设置读标识
        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
        	// 设置读操作位
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     */
    // 将buf转换成指定的ByteBuf类型
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        // 可读字节数为0
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            // 返回空ByteBuf对象
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
        	// ByteBufAllocator是基于内存池
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            // 将buf可读字节复制进directBuf中去
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            // 使得buf引用计数器递减
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    @Override// 关闭SocketChannel连接
    protected void doClose() throws Exception {
    	// 获取connectPromise
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
        	// 设置Failure状态
            promise.tryFailure(DO_CLOSE_CLOSED_CHANNEL_EXCEPTION);
            connectPromise = null;
        }

        // 连接超时
        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
        	// 撤销
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
