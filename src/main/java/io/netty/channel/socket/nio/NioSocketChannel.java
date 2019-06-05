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

	// Ԫ����
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    
    // ��̬����-����SocketChannelʵ��
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    // ����SocketChannelʵ��
    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
        	// �����ͻ���socket�׽���������
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    // SocketChannel������
    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
    	// ����NioSocketChannelʵ��
        this(newSocket(DEFAULT_SELECTOR_PROVIDER));
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    // ����SocketChannel����ͨ��
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    // ������ʵ��NioSocketChannel
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
        super(parent, socket); // --> AbstractNioByteChannel��
        // ��ʼ�����ö���
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    @Override// ��ȡ��ServerSocketChannel����
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override// ��ȡԪ����
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override// ��ȡ���ö���
    public SocketChannelConfig config() {
        return config;
    }

    @Override// ��ȡSocketChannel����
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override// �Ƿ��Ծ״̬��
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        // �ж��Ƿ��ڴ�״̬�л��ߴ�������״̬��
        return ch.isOpen() && ch.isConnected();
    }

    @Override// �Ƿ�ر�״̬��
    public boolean isInputShutdown() {
        return super.isInputShutdown();
    }

    @Override// ���ص�ַ
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override// Զ�̷�������ַ
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @UnstableApi
    @Override// ������Ĺر������
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
        	// socket.shutdownOutput()�ر������,��socket��Ȼ������״̬,���Ӳ�δ�ر�,������Ĺر���;
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    @Override// �ж��Ƿ���������ر���
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override// �ر������
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override // �ر������
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
        	// ����unsafe����,ִ��shutdownOutput(promise)����
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
        	// �첽���ִ��
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
    	// ����socket��ȡSocketAddress��ַ
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
    	// ����socket��ȡ����SocketAddress��ַ
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override// �󶨷�������ip:port��ַ��Ϣ
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
        	// Socket�󶨶˿ں�
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override// ����Զ�̷������ĵ�ַ
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        // �󶨱��ض˿�
    	if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            if (!connected) {
            	// ��ʱû�������ϣ������û�з���ACKӦ�����ӽ����ȷ��������false:����ΪOP_CONNECT,�������ӽ��
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected; // ���ӳɹ�������TRUE
        } finally {
        	// ��ʹreturnҲ��ִ�У�
            if (!success) {
            	// ���Ӳ��ɹ�ʱ
                doClose();
            }
        }
    }

    @Override// ����������������ACK�ظ���ʱʱ,�ᱻ����
    protected void doFinishConnect() throws Exception {
    	// �ж����ӽ��,����ʧ���׳�Error
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override// ȡ������
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override// �ر���Ӧ��Socket����
    protected void doClose() throws Exception {
        super.doClose();
        javaChannel().close();
    }

    @Override// ��SocketChannel�ж�ȡL���ֽڵ�byteBuf�У�LΪbyteBuf��д���ֽ���
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
    	// byteBuf.writableBytes()Ϊ��д������ֽ���
        return byteBuf.writeBytes(javaChannel(), byteBuf.writableBytes());
    }

    @Override// ��SocketChannel��д���ֽ�
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    @Override// �ļ�����
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transfered();
        return region.transferTo(javaChannel(), position);
    }

    @Override// ��SocketChannelд���ֽ�
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (;;) {
        	// ��ȡ�����͵�ByteBuf����
            int size = in.size();
            if (size == 0) {
                // All written so clear OP_WRITE
                clearOpWrite();
                break;
            }
            
            // ��д�ֽ���
            long writtenBytes = 0;
            // �Ƿ�����ɱ�ʶ
            boolean done = false;
            // д�����ʶλ
            boolean setOpWrite = false;

            // Ensure the pending writes are made of ByteBufs only.
            ByteBuffer[] nioBuffers = in.nioBuffers();
            
            // ��ȡ��Ҫ���͵�ByteBuffer�������
            int nioBufferCnt = in.nioBufferCount();
            // ��ȡ��ChannelOutboundBuffer�����ֽ���
            long expectedWrittenBytes = in.nioBufferSize();
            // ��ȡsocket������
            SocketChannel ch = javaChannel();

            // Always us nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) {
                case 0:
                    // We have something else beside ByteBuffers to write so fallback to normal writes.
                    // ���ø����д�뷽��
                	super.doWrite(in);
                    return;
                case 1:
                    // Only one ByteBuf so use non-gathering write
                    ByteBuffer nioBuffer = nioBuffers[0];
                    // ѭ����������,��һ��������ִ��,��ֹ�߳��ڴ����нϳ�ʱ��
                    for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                        final int localWrittenBytes = ch.write(nioBuffer);
                        // ��localWrittenBytesΪOʱ,��ʾ���ͻ���������
                        if (localWrittenBytes == 0) {
                        	// ����д�����ʶ
                            setOpWrite = true;
                            break;
                        }
                        // ��ȥ��д�ֽ���
                        expectedWrittenBytes -= localWrittenBytes;
                        // ��¼��д�ֽ���
                        writtenBytes += localWrittenBytes;
                        // ��expectedWrittenBytesΪOʱ����ʾ�����������
                        if (expectedWrittenBytes == 0) {
                            done = true;
                            break;
                        }
                    }
                    break;
                default:
                    for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                    	// ���ֽ����дӸ�����������������д���ͨ����
                    	// write(ByteBuffer[] srcs, int offset, int length)
                        final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                        // localWrittenBytes == 0��ʾTCP���ͻ���������
                        if (localWrittenBytes == 0) {
                        	// ����д�����ʶ
                            setOpWrite = true;
                            break;
                        }
                        // ��¼ʣ���д���ֽ���
                        expectedWrittenBytes -= localWrittenBytes;
                        // ��¼��д�ֽ���
                        writtenBytes += localWrittenBytes;
                        // ��expectedWrittenBytes == 0ʱ,��ʾд�������
                        if (expectedWrittenBytes == 0) {
                            done = true;// ���ñ��
                            break;
                        }
                    }
                    break;
            }

            // Release the fully written buffers, and update the indexes of the partially written buffer.
            // ɾ����д�ֽ���,������Ӧ�Ķ�д����
            in.removeBytes(writtenBytes);

            if (!done) {
                // Did not write all buffers completely.
            	// ����д�����ʶ
                incompleteWrite(setOpWrite);
                break;
            }
        }
    }

    @Override// ����NioSocketChannelUnsafe����
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

    // ˽���ڲ���
    private final class NioSocketChannelConfig  extends DefaultSocketChannelConfig {
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket); // -->DefaultSocketChannelConfig��
        }

        @Override
        protected void autoReadCleared() {
            setReadPending(false);
        }
    }
}
