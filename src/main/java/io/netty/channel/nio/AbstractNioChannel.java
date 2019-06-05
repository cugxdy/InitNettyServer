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

    // socket����ServerSocketChannel|SocketChannel
    private final SelectableChannel ch;
    // socket״̬��ʶ��
    protected final int readInterestOp;
    // selector��ѯ�����׽���������
    volatile SelectionKey selectionKey;
    // ����������
    private volatile boolean inputShutdown;
    // ���ڶ�ȡ���ݽ�����
    private volatile boolean readPending;

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    // �첽��������������
    private ChannelPromise connectPromise;
    // �첽���ӳ�ʱ��ʱ��
    private ScheduledFuture<?> connectTimeoutFuture;
    // �������Զ�̵�ַ
    private SocketAddress requestedRemoteAddress;

    
    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    // ����SelectableChannel��ز���
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent); // --> AbstractChannel��
        this.ch = ch;
        // ����accept/read/write״̬(read)
        this.readInterestOp = readInterestOp;
        try {
        	// ���÷�����ģʽ
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
            	// �ر�SocketChannelͨ��
                ch.close();
            } catch (IOException e2) {
            	// �׳��쳣
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "Failed to close a partially initialized socket.", e2);
                }
            }
            // ʧ�ܽ��������ģʽ
            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override// �ж�SocketChannel�Ƿ��
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override// ����unsafeʵ��
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    // ����SelectableChannel����
    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override// �����߳�ִ����
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    // ��ȡselectionKey
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    // �ж��Ƿ���read������
    protected boolean isReadPending() {
        return readPending;
    }

    // ����readPending״̬
    protected void setReadPending(boolean readPending) {
        this.readPending = readPending;
    }

    /**
     * Return {@code true} if the input of this {@link Channel} is shutdown
     */
    // �ж��Ƿ��ڽ�ֹ�����״̬��
    protected boolean isInputShutdown() {
        return inputShutdown;
    }

    /**
     * Shutdown the input of this {@link Channel}.
     */
    // ����������Ϊtrue
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

    	// ������������Ƴ�������λ
        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            if (!key.isValid()) {
                return;
            }
            int interestOps = key.interestOps();
            // �жϵ�ǰ�������λ�Ƿ�����ֶ�ֵ
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
            	// �����Ƴ�������λ
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override// ��ȡSocketChannel����
        public final SelectableChannel ch() {
            return javaChannel();
        }

        @Override// �������Ӳ���,����Զ�̷�����
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
            	// �Ѿ�����һ������δ����,�׳��쳣
                if (connectPromise != null) {
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                // ����false
                boolean wasActive = isActive();
                // �������Ӳ���
                if (doConnect(remoteAddress, localAddress)) {
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    connectPromise = promise;
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    // ��ȡ��������ʱʱ��
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    
                    // �������ӳ�ʱʱ�����ö�ʱ����,��ʱʱ�䵽֮�󴥷�����,����������Ӳ�û�����,��ر����Ӿ��
                    // �ͷ���Դ,�����쳣��ջ������ע��
                    if (connectTimeoutMillis > 0) {
                    	// ������ʱ������������������
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                            	// ��ȡconnectPromise
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                // �������ӳ�ʱ�쳣
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                // ��connectPromise����Ϊʧ��״̬
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    // �������ӽ��������,������յ��������֪ͨ�ж������Ƿ�ȡ��,�����ȡ����ر����Ӿ��,�ͷ���Դ,����ȡ��ע�����
                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                        	// ִ���ͷ���Դ
                            if (future.isCancelled()) {
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false);
                                }
                                // ��Ϊ��
                                connectPromise = null;
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
            	// ����Ϊʧ��״̬
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        // ������SocketChannel�޸�Ϊ����������λ,������������Ķ��¼�
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
        	// promise����Ϊ��
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            // ����true
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            // ���û���������ʱ,trySuccess()���᷵��false
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
            	// ����ChannelActive�¼�
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
            	// �ر�SocketChannel
                close(voidPromise());
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            // ����Ϊʧ��״̬
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
                // �ж��Ƿ��������
                doFinishConnect();
                // �������fireChannelActive�¼�
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
            	// ��connectPromise����Ϊʧ��״̬
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
            	// ������ӳ�ʱʱ��Ȼû�н��յ�����˵�ACK��ϢӦ����Ϣ,���ɶ�ʱ����رտͻ�������,��SocketChannel��
            	// Reactor�̵߳Ķ�·��������ժ��,�ͷ���Դ��
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
        	// �ж��Ƿ�������flush״̬��
            if (isFlushPending()) {
                return;
            }
            super.flush0();
        }

        @Override// ִ��flush
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        // �ж��������λ�Ƿ�ΪOP_WRITE״̬
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override// �ж��Ƿ�ΪNioEventLoop����
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    // ��socketע�ᵽselector��ѯ������ȥ
    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (;;) {
            try {
            	// ע����Selector��,�������ע�����
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                	// ��ȡ����selectionKey�Ӷ�·��������ɾ��
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

    @Override// ȡ��ע��
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }

    @Override// ׼�����������֮ǰ��Ҫ�����������λΪ��
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
    	// �ж��Ƿ��ֹread����
        if (inputShutdown) {
            return;
        }

        final SelectionKey selectionKey = this.selectionKey;
        // ����selectionKey�Ƿ����
        if (!selectionKey.isValid()) {
            return;
        }

        readPending = true;
        // ���ö���ʶ
        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
        	// ���ö�����λ
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
    // ��bufת����ָ����ByteBuf����
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        // �ɶ��ֽ���Ϊ0
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            // ���ؿ�ByteBuf����
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
        	// ByteBufAllocator�ǻ����ڴ��
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            // ��buf�ɶ��ֽڸ��ƽ�directBuf��ȥ
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            // ʹ��buf���ü������ݼ�
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

    @Override// �ر�SocketChannel����
    protected void doClose() throws Exception {
    	// ��ȡconnectPromise
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
        	// ����Failure״̬
            promise.tryFailure(DO_CLOSE_CLOSED_CHANNEL_EXCEPTION);
            connectPromise = null;
        }

        // ���ӳ�ʱ
        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
        	// ����
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
