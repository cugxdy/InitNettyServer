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
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {

    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    // ������д�������
    private Runnable flushTask;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);// --> AbstractNioChannel
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {
        private RecvByteBufAllocator.Handle allocHandle;

        // �رն�����
        private void closeOnRead(ChannelPipeline pipeline) {
            SelectionKey key = selectionKey();
            // ����������
            setInputShutdown();
            // �ж�SocketChannel�Ƿ��ڴ�״̬��
            if (isOpen()) {
                if (Boolean.TRUE.equals(config().getOption(ChannelOption.ALLOW_HALF_CLOSURE))) {
                	// ȡ��readInterestOp�������λ
                    key.interestOps(key.interestOps() & ~readInterestOp);
                    // ������Ӧ�¼�
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            }
        }

        // ������쳣
        private void handleReadException(ChannelPipeline pipeline,
                                         ByteBuf byteBuf, Throwable cause, boolean close) {
            if (byteBuf != null) {
            	// �Ƿ�ɶ�
                if (byteBuf.isReadable()) {
                	// �رն�ȡ����
                    setReadPending(false);
                    // ������ȡ��ɲ���
                    pipeline.fireChannelRead(byteBuf);
                } else {
                	// �������ü���
                    byteBuf.release();
                }
            }
            // ����������¼�
            pipeline.fireChannelReadComplete();
            // �����쳣�¼�
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
        	// ��ȡNioSocketChannel��SocketChannelConfig,����Ҫ�������ÿͻ������ӵ�TCP����
            final ChannelConfig config = config();
            
            if (!config.isAutoRead() && !isReadPending()) {
                // ChannelConfig.setAutoRead(false) was called in the meantime
                removeReadOp();
                return;
            }

            final ChannelPipeline pipeline = pipeline();
            // ��ȡ�ڴ������
            final ByteBufAllocator allocator = config.getAllocator();
            // ÿ�������ȡ������ֽ���
            final int maxMessagesPerRead = config.getMaxMessagesPerRead();
            
            RecvByteBufAllocator.Handle allocHandle = this.allocHandle;
            if (allocHandle == null) {
                this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle();
            }

            ByteBuf byteBuf = null;
            int messages = 0;
            boolean close = false;
            try {
            	// �ܼƶ�ȡ�ֽ���
                int totalReadAmount = 0;
                boolean readPendingReset = false;
                do {
                	// �����洢���ݵ�ByteBuf����[���м�¼ÿ������Ĵ�С]
                    byteBuf = allocHandle.allocate(allocator);
                    
                    // ��д�ֽ���
                    int writable = byteBuf.writableBytes();
                    // ���ջ�����byteBuf������ɺ�,������Ϣ���첽��ȡ
                    int localReadAmount = doReadBytes(byteBuf);
                    
                    if (localReadAmount <= 0) {
                        // not was read release the buffer
                    	// �ͷŽ��ջ�����
                        byteBuf.release();
                        byteBuf = null;
                        close = localReadAmount < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                        	// �ر�����,�ͷž����Դ
                            setReadPending(false);
                        }
                        // �ж�ѭ��
                        break;
                    }
                    if (!readPendingReset) {
                        readPendingReset = true;
                        setReadPending(false);
                    }
                    // ��ʹ�ð���������,ChannelRead��������Ϣ��һһ��Ӧ��ϵ
                    pipeline.fireChannelRead(byteBuf);
                    // ���ջ�������Ϊ��
                    byteBuf = null;

                    // ����ۼƶ�ȡ���ֽ����������,�򽫶�ȡ�����ֽ�������Ϊ���͵����ֵ
                    if (totalReadAmount >= Integer.MAX_VALUE - localReadAmount) {
                        // Avoid overflow.
                        totalReadAmount = Integer.MAX_VALUE;
                        // �˳�����ѭ��,��ֹӰ������Ŷӵ�Task�����д������ִ�С�
                        break;
                    }

                    // ÿ�ζ�ȡ����֮��,��Զ�ȡ���ֽ��������ۼ�
                    totalReadAmount += localReadAmount;

                    // stop reading
                    if (!config.isAutoRead()) {
                        break;
                    }
            
                    if (localReadAmount < writable) {
                        // Read less than what the buffer can hold,
                        // which might mean we drained the recv buffer completely.
                        break;
                    }
                // ������Ϊ16
                } while (++ messages < maxMessagesPerRead);

                // ִ�ж�ȡ�ɹ��ص�����
                pipeline.fireChannelReadComplete();
                
                // ���ý��ջ�����������������Handler�ļ�¼����,�����ζ�ȡ�����ֽ������뵽Record()�����н��л������Ķ�̬����
                // Ϊ��һ�ζ�ȡѡȡ���Ӻ��ʵĻ���������
                allocHandle.record(totalReadAmount);

                if (close) {
                    closeOnRead(pipeline);
                    close = false;
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!config.isAutoRead() && !isReadPending()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = -1;

        // д�����ʶ
        boolean setOpWrite = false;
        for (;;) {
        	// ��ChannelOutboundBufferȡ��һ����Ϣ
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
            	// ��Ϣ�������������д����͵���Ϣ���Ѿ��������
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            // �Ƿ�ΪByteBuf����
            if (msg instanceof ByteBuf) {
            	
                ByteBuf buf = (ByteBuf) msg;
                // ���buf�пɶ��ֽ���
                int readableBytes = buf.readableBytes();
                if (readableBytes == 0) {
                	// ���ɶ��ֽ���Ϊ0ʱ,��ChannelOutboundBufferɾ����buf
                    in.remove(); 
                    continue;
                }

                // ��Ϣ�Ƿ�ȫ�����ͱ�ʶ
                boolean done = false;
                // ���͵�����Ϣ�ֽ���
                long flushedAmount = 0;
                if (writeSpinCount == -1) {
                	// ��Channel���ö����л�ȡѭ�����ʹ���,��ֹ�̼߳���
                    writeSpinCount = config().getWriteSpinCount();
                }
                for (int i = writeSpinCount - 1; i >= 0; i --) {
                	// ����doWriteBytes������Ϣ����
                    int localFlushedAmount = doWriteBytes(buf);
                    // ��localFlushedAmountΪ0ʱ,��˵������TCP������������������ZERO_WINDOW
                    // ����д�����ʶΪtrue���˳�ѭ�����ͷ�I/O�̣߳���ֹ���ֿ�ѭ��,ռ��CPU��Դ
                    if (localFlushedAmount == 0) {
                        setOpWrite = true;
                        break;
                    }
                    // �ۼ��ѷ����ֽ���
                    flushedAmount += localFlushedAmount;
                    // �жϵ�ǰ��Ϣ�Ƿ��Ѿ������ɹ�
                    if (!buf.isReadable()) {
                    	// ����done = true,�˳�ѭ��
                        done = true;
                        break;
                    }
                }
                // ���·��ͽ�����Ϣ
                in.progress(flushedAmount);
                
                // ������ɣ��ʹӻ�������ɾ��
                if (done) {
                    in.remove();
                } else {
                    // Break the loop and so incompleteWrite(...) is called.
                    break;
                }
            } else if (msg instanceof FileRegion) {
                FileRegion region = (FileRegion) msg;
                boolean done = region.transfered() >= region.count();

                if (!done) {
                    long flushedAmount = 0;
                    if (writeSpinCount == -1) {
                        writeSpinCount = config().getWriteSpinCount();
                    }

                    for (int i = writeSpinCount - 1; i >= 0; i--) {
                        long localFlushedAmount = doWriteFileRegion(region);
                        if (localFlushedAmount == 0) {
                            setOpWrite = true;
                            break;
                        }

                        flushedAmount += localFlushedAmount;
                        if (region.transfered() >= region.count()) {
                            done = true;
                            break;
                        }
                    }

                    in.progress(flushedAmount);
                }

                if (done) {
                    in.remove();
                } else {
                    // Break the loop and so incompleteWrite(...) is called.
                    break;
                }
            } else {
                // Should not reach here.
                throw new Error();
            }
        }
        // ��������������
        incompleteWrite(setOpWrite);
    }

    @Override// ���������Ϣ
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            // �����msgת����Direct���͵�
            return newDirectBuffer(buf);
        }

        // FileRegion����ֱ�Ӿͷ���
        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    // ����д�������
    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
    	// �ж��Ƿ���Ҫ����д�����ʶ
        if (setOpWrite) {
            setOpWrite();
        } else {
            // Schedule flush again later so other tasks can be picked up in the meantime
        	// ��װ��������������������ȥִ��
            Runnable flushTask = this.flushTask;
            if (flushTask == null) {
                flushTask = this.flushTask = new Runnable() {
                    @Override
                    public void run() {
                        flush();
                    }
                };
            }
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    // д�ļ�������ʵ��
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    // ������������ʵ��
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    // д����������ʵ��
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
    	// ���ȡSelectionKey�ֶ�
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        // ��֤�Ƿ�Ϸ�
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
        	// ��SelectionKey����Ϊ��д��
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
    	// ȡ����ǰselectionKey�������λ
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        // ��ȡ�������λ
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
        	// ע��д��ʶ
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
