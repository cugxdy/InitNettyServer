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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.UnstableApi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executor;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {

	// 元数据
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    
    // 静态常量-创建SocketChannel实例
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    // 创建SocketChannel实例
    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
        	// 创建客户端socket套接字描述符
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    // SocketChannel配置类
    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
    	// 创建NioSocketChannel实例
        this(newSocket(DEFAULT_SELECTOR_PROVIDER));
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    // 创建SocketChannel连接通道
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    // 创建新实例NioSocketChannel
    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, SocketChannel socket) {
        super(parent, socket); // --> AbstractNioByteChannel类
        // 初始化配置对象
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    @Override// 获取父ServerSocketChannel对象
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override// 获取元数据
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override// 获取配置对象
    public SocketChannelConfig config() {
        return config;
    }

    @Override// 获取SocketChannel对象
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override// 是否活跃状态下
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        // 判断是否处于打开状态中或者处于连接状态中
        return ch.isOpen() && ch.isConnected();
    }

    @Override// 是否关闭状态下
    public boolean isInputShutdown() {
        return super.isInputShutdown();
    }

    @Override// 本地地址
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override// 远程服务器地址
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @UnstableApi
    @Override// 单方向的关闭输出流
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
        	// socket.shutdownOutput()关闭输出流,但socket仍然是连接状态,连接并未关闭,单方向的关闭流;
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    @Override// 判断是否处于输出流关闭中
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override// 关闭输出流
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override // 关闭输出流
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
        	// 返回unsafe对象,执行shutdownOutput(promise)方法
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
        	// 异步编程执行
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    protected SocketAddress localAddress0() {
    	// 依据socket获取SocketAddress地址
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
    	// 依据socket获取服务SocketAddress地址
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override// 绑定服务器的ip:port地址信息
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
        	// Socket绑定端口号
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override// 连接远程服务器的地址
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        // 绑定本地端口
    	if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            if (!connected) {
            	// 暂时没有连接上，服务端没有返回ACK应答，连接结果不确定，返回false:设置为OP_CONNECT,监听连接结果
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected; // 连接成功，返回TRUE
        } finally {
        	// 即使return也会执行！
            if (!success) {
            	// 连接不成功时
                doClose();
            }
        }
    }

    @Override// 当服务器连接请求ACK回复延时时,会被调用
    protected void doFinishConnect() throws Exception {
    	// 判断连接结果,连接失败抛出Error
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override// 取消连接
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override// 关闭相应的Socket连接
    protected void doClose() throws Exception {
        super.doClose();
        javaChannel().close();
    }

    @Override// 从SocketChannel中读取L个字节到byteBuf中，L为byteBuf可写的字节数
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
    	// byteBuf.writableBytes()为可写的最大字节数
        return byteBuf.writeBytes(javaChannel(), byteBuf.writableBytes());
    }

    @Override// 向SocketChannel中写入字节
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    @Override// 文件传输
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transfered();
        return region.transferTo(javaChannel(), position);
    }

    @Override// 向SocketChannel写入字节
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (;;) {
        	// 获取待发送的ByteBuf个数
            int size = in.size();
            if (size == 0) {
                // All written so clear OP_WRITE
                clearOpWrite();
                break;
            }
            
            // 已写字节数
            long writtenBytes = 0;
            // 是否发送完成标识
            boolean done = false;
            // 写半包标识位
            boolean setOpWrite = false;

            // Ensure the pending writes are made of ByteBufs only.
            ByteBuffer[] nioBuffers = in.nioBuffers();
            
            // 获取需要发送的ByteBuffer数组个数
            int nioBufferCnt = in.nioBufferCount();
            // 获取从ChannelOutboundBuffer的总字节数
            long expectedWrittenBytes = in.nioBufferSize();
            // 获取socket描述符
            SocketChannel ch = javaChannel();

            // Always us nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) {
                case 0:
                    // We have something else beside ByteBuffers to write so fallback to normal writes.
                    // 调用父类的写入方法
                	super.doWrite(in);
                    return;
                case 1:
                    // Only one ByteBuf so use non-gathering write
                    ByteBuffer nioBuffer = nioBuffers[0];
                    // 循环发送数据,在一定次数内执行,防止线程在此运行较长时间
                    for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                        final int localWrittenBytes = ch.write(nioBuffer);
                        // 当localWrittenBytes为O时,表示发送缓冲区已满
                        if (localWrittenBytes == 0) {
                        	// 设置写半包标识
                            setOpWrite = true;
                            break;
                        }
                        // 减去已写字节数
                        expectedWrittenBytes -= localWrittenBytes;
                        // 记录已写字节数
                        writtenBytes += localWrittenBytes;
                        // 当expectedWrittenBytes为O时，表示发送数据完成
                        if (expectedWrittenBytes == 0) {
                            done = true;
                            break;
                        }
                    }
                    break;
                default:
                    for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                    	// 将字节序列从给定缓冲区的子序列写入此通道。
                    	// write(ByteBuffer[] srcs, int offset, int length)
                        final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                        // localWrittenBytes == 0表示TCP发送缓冲区已满
                        if (localWrittenBytes == 0) {
                        	// 设置写半包标识
                            setOpWrite = true;
                            break;
                        }
                        // 记录剩余待写的字节数
                        expectedWrittenBytes -= localWrittenBytes;
                        // 记录已写字节数
                        writtenBytes += localWrittenBytes;
                        // 当expectedWrittenBytes == 0时,表示写操作完成
                        if (expectedWrittenBytes == 0) {
                            done = true;// 设置标记
                            break;
                        }
                    }
                    break;
            }

            // Release the fully written buffers, and update the indexes of the partially written buffer.
            // 删除已写字节数,更新相应的读写索引
            in.removeBytes(writtenBytes);

            if (!done) {
                // Did not write all buffers completely.
            	// 处理写半包标识
                incompleteWrite(setOpWrite);
                break;
            }
        }
    }

    @Override// 返回NioSocketChannelUnsafe对象
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }

    private final class NioSocketChannelUnsafe extends NioByteUnsafe {
        @Override
        protected Executor prepareToClose() {
            try {
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // We need to cancel this key of the channel so we may not end up in a eventloop spin
                    // because we try to read or write until the actual close happens which may be later due
                    // SO_LINGER handling.
                    // See https://github.com/netty/netty/issues/4449
                    doDeregister();
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
                // Ignore the error as the underlying channel may be closed in the meantime and so
                // getSoLinger() may produce an exception. In this case we just return null.
                // See https://github.com/netty/netty/issues/4449
            }
            return null;
        }
    }

    // 私有内部类
    private final class NioSocketChannelConfig  extends DefaultSocketChannelConfig {
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket); // -->DefaultSocketChannelConfig类
        }

        @Override
        protected void autoReadCleared() {
            setReadPending(false);
        }
    }
}
