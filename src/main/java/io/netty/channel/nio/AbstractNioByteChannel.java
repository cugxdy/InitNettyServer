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

    // 负责处理写半包任务
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

        // 关闭读操作
        private void closeOnRead(ChannelPipeline pipeline) {
            SelectionKey key = selectionKey();
            // 禁用输入流
            setInputShutdown();
            // 判断SocketChannel是否处于打开状态中
            if (isOpen()) {
                if (Boolean.TRUE.equals(config().getOption(ChannelOption.ALLOW_HALF_CLOSURE))) {
                	// 取消readInterestOp网络操作位
                    key.interestOps(key.interestOps() & ~readInterestOp);
                    // 触发相应事件
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            }
        }

        // 处理读异常
        private void handleReadException(ChannelPipeline pipeline,
                                         ByteBuf byteBuf, Throwable cause, boolean close) {
            if (byteBuf != null) {
            	// 是否可读
                if (byteBuf.isReadable()) {
                	// 关闭读取操作
                    setReadPending(false);
                    // 触发读取完成操作
                    pipeline.fireChannelRead(byteBuf);
                } else {
                	// 减少引用计数
                    byteBuf.release();
                }
            }
            // 触发读完成事件
            pipeline.fireChannelReadComplete();
            // 触发异常事件
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
        	// 获取NioSocketChannel的SocketChannelConfig,它主要用于设置客户端连接的TCP参数
            final ChannelConfig config = config();
            
            if (!config.isAutoRead() && !isReadPending()) {
                // ChannelConfig.setAutoRead(false) was called in the meantime
                removeReadOp();
                return;
            }

            final ChannelPipeline pipeline = pipeline();
            // 获取内存分配器
            final ByteBufAllocator allocator = config.getAllocator();
            // 每次允许读取的最大字节数
            final int maxMessagesPerRead = config.getMaxMessagesPerRead();
            
            RecvByteBufAllocator.Handle allocHandle = this.allocHandle;
            if (allocHandle == null) {
                this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle();
            }

            ByteBuf byteBuf = null;
            int messages = 0;
            boolean close = false;
            try {
            	// 总计读取字节数
                int totalReadAmount = 0;
                boolean readPendingReset = false;
                do {
                	// 创建存储数据的ByteBuf对象[其中记录每次申请的大小]
                    byteBuf = allocHandle.allocate(allocator);
                    
                    // 可写字节数
                    int writable = byteBuf.writableBytes();
                    // 接收缓存区byteBuf分配完成后,进行消息的异步读取
                    int localReadAmount = doReadBytes(byteBuf);
                    
                    if (localReadAmount <= 0) {
                        // not was read release the buffer
                    	// 释放接收缓存区
                        byteBuf.release();
                        byteBuf = null;
                        close = localReadAmount < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                        	// 关闭连接,释放句柄资源
                            setReadPending(false);
                        }
                        // 中断循环
                        break;
                    }
                    if (!readPendingReset) {
                        readPendingReset = true;
                        setReadPending(false);
                    }
                    // 当使用半包处理机制,ChannelRead与完整信息是一一对应关系
                    pipeline.fireChannelRead(byteBuf);
                    // 接收缓存区置为空
                    byteBuf = null;

                    // 如果累计读取的字节数发生溢出,则将读取到的字节数设置为整型的最大值
                    if (totalReadAmount >= Integer.MAX_VALUE - localReadAmount) {
                        // Avoid overflow.
                        totalReadAmount = Integer.MAX_VALUE;
                        // 退出本次循环,防止影响后面排队的Task任务和写操作的执行。
                        break;
                    }

                    // 每次读取操作之后,会对读取的字节数进行累加
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
                // 最大次数为16
                } while (++ messages < maxMessagesPerRead);

                // 执行读取成功回调函数
                pipeline.fireChannelReadComplete();
                
                // 调用接收缓存区容量分配器的Handler的记录方法,将本次读取的总字节数传入到Record()方法中进行缓存区的动态分配
                // 为下一次读取选取更加合适的缓存区容量
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

        // 写半包标识
        boolean setOpWrite = false;
        for (;;) {
        	// 从ChannelOutboundBuffer取出一条信息
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
            	// 消息发送数组中所有待发送的信息都已经发送完成
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            // 是否为ByteBuf类型
            if (msg instanceof ByteBuf) {
            	
                ByteBuf buf = (ByteBuf) msg;
                // 检测buf中可读字节数
                int readableBytes = buf.readableBytes();
                if (readableBytes == 0) {
                	// 当可读字节数为0时,从ChannelOutboundBuffer删除该buf
                    in.remove(); 
                    continue;
                }

                // 消息是否全部发送标识
                boolean done = false;
                // 发送的总消息字节数
                long flushedAmount = 0;
                if (writeSpinCount == -1) {
                	// 从Channel配置对象中获取循环发送次数,防止线程假死
                    writeSpinCount = config().getWriteSpinCount();
                }
                for (int i = writeSpinCount - 1; i >= 0; i --) {
                	// 调用doWriteBytes进行消息发送
                    int localFlushedAmount = doWriteBytes(buf);
                    // 当localFlushedAmount为0时,则说明发送TCP缓存区已满，发生了ZERO_WINDOW
                    // 设置写半包标识为true，退出循环，释放I/O线程，防止出现空循环,占用CPU资源
                    if (localFlushedAmount == 0) {
                        setOpWrite = true;
                        break;
                    }
                    // 累计已发送字节数
                    flushedAmount += localFlushedAmount;
                    // 判断当前消息是否已经发生成功
                    if (!buf.isReadable()) {
                    	// 设置done = true,退出循环
                        done = true;
                        break;
                    }
                }
                // 更新发送进度消息
                in.progress(flushedAmount);
                
                // 发送完成，就从缓存区中删除
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
        // 处理半包发送任务
        incompleteWrite(setOpWrite);
    }

    @Override// 过滤输出消息
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            // 将输出msg转换成Direct类型的
            return newDirectBuffer(buf);
        }

        // FileRegion类型直接就返回
        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    // 处理写半包问题
    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
    	// 判断是否需要设置写半包标识
        if (setOpWrite) {
            setOpWrite();
        } else {
            // Schedule flush again later so other tasks can be picked up in the meantime
        	// 封装成任务添加至任务队列中去执行
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
    // 写文件由子类实现
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    // 读数据由子类实现
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    // 写数据由子类实现
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
    	// 或获取SelectionKey字段
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        // 验证是否合法
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
        	// 将SelectionKey设置为可写的
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
    	// 取出当前selectionKey网络操作位
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        // 获取网络操作位
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
        	// 注销写标识
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
