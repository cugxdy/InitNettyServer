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
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.Recycler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.OrderedEventExecutor;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.channel.DefaultChannelPipeline.*;

abstract class AbstractChannelHandlerContext extends DefaultAttributeMap implements ChannelHandlerContext {

	// ָ����ָ��AbstractChannelHandlerContext
    volatile AbstractChannelHandlerContext next;
    
    // ָ��ǰ��ָ��AbstractChannelHandlerContext
    volatile AbstractChannelHandlerContext prev;

    // ԭ���Ը���handlerState���ֶ�ֵ
    private static final AtomicIntegerFieldUpdater<AbstractChannelHandlerContext> HANDLER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannelHandlerContext.class, "handlerState");

    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} is about to be called.
     */
    // ����Add������
    private static final int ADD_PENDING = 1;
    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called.
     */
    // Add���ʱ
    private static final int ADD_COMPLETE = 2;
    /**
     * {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    // Remove���ʱ
    private static final int REMOVE_COMPLETE = 3;
    /**
     * Neither {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}
     * nor {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    // ��ʼ��״̬
    private static final int INIT = 0;

    // ��ջ������
    private final boolean inbound;
    
    // ��ջ������
    private final boolean outbound;

    // ����DefaultChannelPipeline����
    private final DefaultChannelPipeline pipeline;
    
    // handler����
    private final String name;
    
    private final boolean ordered;

    // Will be set to null if no child executor should be used, otherwise it will be set to the
    // child executor.
    // �߳�ִ����
    final EventExecutor executor;
    private ChannelFuture succeededFuture;

    // Lazily instantiated tasks used to trigger events to a handler with different executor.
    // There is no need to make this volatile as at worse it will just create a few more instances then needed.
    private Runnable invokeChannelReadCompleteTask;
    private Runnable invokeReadTask;
    private Runnable invokeChannelWritableStateChangedTask;
    private Runnable invokeFlushTask;

    // hanlder״̬
    private volatile int handlerState = INIT;

    AbstractChannelHandlerContext(DefaultChannelPipeline pipeline, EventExecutor executor, String name,
                                  boolean inbound, boolean outbound) {
    	// ��������
        this.name = ObjectUtil.checkNotNull(name, "name");
        // ����DefaultChannelPipeline����
        this.pipeline = pipeline; //��ֵpipeline(private final DefaultChannelPipeline pipeline)
        // DefaultChannelPipeline ����Channel������,�߳�ִ����
        this.executor = executor;
        
        this.inbound = inbound; //��ջ������
        this.outbound = outbound;//��ջ������
        
        // Its ordered if its driven by the EventLoop or the given Executor is an instanceof OrderedEventExecutor.
        ordered = executor == null || executor instanceof OrderedEventExecutor;
    }

    @Override// ��ȡSocketChannel����
    public Channel channel() {
        return pipeline.channel();
    }

    @Override// ��ȡDefaultChannelPipeline����
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override// ��ȡByteBufAllocator����
    public ByteBufAllocator alloc() {
        return channel().config().getAllocator();
    }

    @Override// ��ȡ�߳�ִ����
    public EventExecutor executor() {
    	// executorΪ��ʱ,��ȡChannel������eventLoop
        if (executor == null) {
            return channel().eventLoop();
        } else {
            return executor;
        }
    }

    @Override// ��ȡ����
    public String name() {
        return name;
    }

    @Override
    public ChannelHandlerContext fireChannelRegistered() {
    	// ִ��invokeChannelRegistere��̬����(Decoder)
        invokeChannelRegistered(findContextInbound());
        return this;
    }

    static void invokeChannelRegistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        
        // ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�̣߳�������ִ�� invokeChannelRegistered ����
            next.invokeChannelRegistered();
        } else { 
        	// ��װ������������������
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRegistered();
                }
            });
        }
    }

    private void invokeChannelRegistered() {
    	// ִ�� Context��Ӧ�� handler�� channelRegistered����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��channelRegistered����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).channelRegistered(this);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireChannelRegistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelUnregistered() {
    	// ִ��invokeChannelUnregistered��̬����(Decoder)
        invokeChannelUnregistered(findContextInbound());
        return this;
    }

    static void invokeChannelUnregistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        // ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�̣߳�������ִ�� invokeChannelUnregistered����
            next.invokeChannelUnregistered();
        } else {
        	// ��װ������������������
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelUnregistered();
                }
            });
        }
    }

    private void invokeChannelUnregistered() {
    	// ִ�� Context��Ӧ�� handler�� channelUnregistered����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��channelRegistered����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).channelUnregistered(this);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireChannelUnregistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelActive() {
    	// ִ��invokeChannelActive��̬����(Decoder)
        invokeChannelActive(findContextInbound());
        return this;
    }

    static void invokeChannelActive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        // ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�̣߳�������ִ�� invokeChannelActive����
            next.invokeChannelActive();
        } else {
        	// ��װ������������������
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelActive();
                }
            });
        }
    }

    private void invokeChannelActive() {
    	// ִ�� Context��Ӧ�� handler�� invokeChannelActive����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��invokeChannelActive����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).channelActive(this);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireChannelActive();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelInactive() {
    	// ִ��invokeChannelInactive��̬����(Decoder)
        invokeChannelInactive(findContextInbound());
        return this;
    }

    static void invokeChannelInactive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        // ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�̣߳�������ִ�� invokeChannelInactive����
            next.invokeChannelInactive();
        } else {
        	// ��װ������������������
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelInactive();
                }
            });
        }
    }

    private void invokeChannelInactive() {
    	// ִ�� Context��Ӧ�� handler�� invokeChannelInactive����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��channelInactive����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).channelInactive(this);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireChannelInactive();
        }
    }

    @Override// ִ��invokeExceptionCaught��̬����
    public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
        invokeExceptionCaught(next, cause);
        return this;
    }

    static void invokeExceptionCaught(final AbstractChannelHandlerContext next, final Throwable cause) {
        // ���cause�Ƿ�Ϊ��
    	ObjectUtil.checkNotNull(cause, "cause");
        EventExecutor executor = next.executor();
     // ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�̣߳�������ִ�� invokeExceptionCaught����
            next.invokeExceptionCaught(cause);
        } else {
            try {
            	// ��װ��������������������ȥ
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        next.invokeExceptionCaught(cause);
                    }
                });
            } catch (Throwable t) {
            	// ��¼��־��Ϣ
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to submit an exceptionCaught() event.", t);
                    logger.warn("The exceptionCaught() event that was failed to submit was:", cause);
                }
            }
        }
    }

    private void invokeExceptionCaught(final Throwable cause) {
    	// ִ�� Context��Ӧ�� handler�� invokeExceptionCaught����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��exceptionCaught����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                handler().exceptionCaught(this, cause);
            } catch (Throwable error) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                        "An exception {}" +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:",
                        ThrowableUtil.stackTraceToString(error), cause);
                } else if (logger.isWarnEnabled()) {
                    logger.warn(
                        "An exception '{}' [enable DEBUG level for full stacktrace] " +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:", error, cause);
                }
            }
        } else {
        	// ��������ִ����
            fireExceptionCaught(cause);
        }
    }

    @Override
    public ChannelHandlerContext fireUserEventTriggered(final Object event) {
    	// ִ��invokeUserEventTriggered��̬����(Decoder)
        invokeUserEventTriggered(findContextInbound(), event);
        return this;
    }

    static void invokeUserEventTriggered(final AbstractChannelHandlerContext next, final Object event) {
        ObjectUtil.checkNotNull(event, "event");
        // ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�߳���,ִ��invokeUserEventTriggered����
            next.invokeUserEventTriggered(event);
        } else {
        	// ��װ���첽������������������
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeUserEventTriggered(event);
                }
            });
        }
    }

    private void invokeUserEventTriggered(Object event) {
    	// ִ�� Context��Ӧ�� handler�� invokeUserEventTriggered����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��userEventTriggered����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).userEventTriggered(this, event);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireUserEventTriggered(event);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelRead(final Object msg) {
    	// ִ��invokeChannelRead��̬����(Decoder)
        invokeChannelRead(findContextInbound(), msg);
        return this;
    }
    static void invokeChannelRead(final AbstractChannelHandlerContext next, final Object msg) {
    	// ����msg�Ƿ�Ϊ��
        ObjectUtil.checkNotNull(msg, "msg");
        // ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�߳���,ִ��invokeChannelRead����
            next.invokeChannelRead(msg);
        } else {
        	// ��װ���첽������������������
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRead(msg);
                }
            });
        }
    }

    private void invokeChannelRead(Object msg) {
    	// ִ�� Context��Ӧ�� handler�� invokeChannelRead����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��channelRead����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).channelRead(this, msg);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireChannelRead(msg);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
    	// ִ��invokeChannelReadComplete��̬����(Decoder)
        invokeChannelReadComplete(findContextInbound());
        return this;
    }

    static void invokeChannelReadComplete(final AbstractChannelHandlerContext next) {
    	// ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�߳���,ִ��invokeChannelReadComplete����
            next.invokeChannelReadComplete();
        } else {
        	// ��װ���첽������������������
            Runnable task = next.invokeChannelReadCompleteTask;
            if (task == null) {
                next.invokeChannelReadCompleteTask = task = new Runnable() {
                    @Override
                    public void run() {
                        next.invokeChannelReadComplete();
                    }
                };
            }
            executor.execute(task);
        }
    }

    private void invokeChannelReadComplete() {
    	// ִ�� Context��Ӧ�� handler�� invokeChannelReadComplete����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��channelReadComplete����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).channelReadComplete(this);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireChannelReadComplete();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelWritabilityChanged() {
    	// ִ��invokeChannelWritabilityChanged��̬����(Decoder)
        invokeChannelWritabilityChanged(findContextInbound());
        return this;
    }

    static void invokeChannelWritabilityChanged(final AbstractChannelHandlerContext next) {
    	// ��ȡִ����EventLoop�������ж��Ƿ��ڵ�ǰ�߳�
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// �ڵ�ǰ�߳���,ִ��invokeChannelWritabilityChanged����
            next.invokeChannelWritabilityChanged();
        } else {
        	// ��װ���첽������������������
            Runnable task = next.invokeChannelWritableStateChangedTask;
            if (task == null) {
                next.invokeChannelWritableStateChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        next.invokeChannelWritabilityChanged();
                    }
                };
            }
            executor.execute(task);
        }
    }

    private void invokeChannelWritabilityChanged() {
    	// ִ�� Context��Ӧ�� handler�� invokeChannelWritabilityChanged����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��channelWritabilityChanged����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelInboundHandler) handler()).channelWritabilityChanged(this);
            } catch (Throwable t) {
            	// �������쳣ʱ
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            fireChannelWritabilityChanged();
        }
    }

    @Override// �󶨱��ص�ַ
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newPromise());
    }

    @Override// ����Զ�̵�ַ
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newPromise());
    }

    @Override// ȡ������
    public ChannelFuture disconnect() {
        return disconnect(newPromise());
    }

    @Override// �ر�����
    public ChannelFuture close() {
        return close(newPromise());
    }

    @Override// ȡ��ע��
    public ChannelFuture deregister() {
        return deregister(newPromise());
    }

    @Override
    public ChannelFuture bind(final SocketAddress localAddress, final ChannelPromise promise) {
    	// ��ָ���쳣
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        // ��֤promise������
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        // ����(output)���͵�AbstractChannelHandlerContext����
        final AbstractChannelHandlerContext next = findContextOutbound();
        // ��ȡ�߳�ִ����
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// ִ��invokeBind����
            next.invokeBind(localAddress, promise);
        } else {
        	// ��װ�ɶ�ʱ����
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeBind(localAddress, promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeBind(SocketAddress localAddress, ChannelPromise promise) {
    	// ִ�� Context��Ӧ�� handler�� invokeBind����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��bind����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelOutboundHandler) handler()).bind(this, localAddress, promise);
            } catch (Throwable t) {
            	// �׳��쳣
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// ��������ִ����
            bind(localAddress, promise);
        }
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return connect(remoteAddress, null, promise);
    }

    @Override
    public ChannelFuture connect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {

    	// ��ָ���쳣
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }
        
    	// ��֤promise������
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }
        // ����(output)���͵�AbstractChannelHandlerContext����
        final AbstractChannelHandlerContext next = findContextOutbound();
        // ��ȡ�߳�ִ����
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// ִ��invokeConnect����
            next.invokeConnect(remoteAddress, localAddress, promise);
        } else {
        	// ��װ�ɶ�ʱ����
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeConnect(remoteAddress, localAddress, promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeConnect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
    	// ִ�� Context��Ӧ�� handler�� invokeConnect����
    	if (invokeHandler()) {
    		// ������������ʱ
            try {
            	// ִ��handler��connect����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelOutboundHandler) handler()).connect(this, remoteAddress, localAddress, promise);
            } catch (Throwable t) {
            	// �׳��쳣
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// ��������ִ����
            connect(remoteAddress, localAddress, promise);
        }
    }

    @Override
    public ChannelFuture disconnect(final ChannelPromise promise) {
    	// ��֤promise������
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }
        
        // ����(output)���͵�AbstractChannelHandlerContext����
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // ��ȡ�߳�ִ����
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            // Translate disconnect to close if the channel has no notion of disconnect-reconnect.
            // So far, UDP/IP is the only transport that has such behavior.
            if (!channel().metadata().hasDisconnect()) {
            	// ִ��invokeClose����
                next.invokeClose(promise);
            } else {
            	// ִ��invokeDisconnect����
                next.invokeDisconnect(promise);
            }
        } else {
        	// ��װ�ɶ�ʱ����
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    if (!channel().metadata().hasDisconnect()) {
                        next.invokeClose(promise);
                    } else {
                        next.invokeDisconnect(promise);
                    }
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeDisconnect(ChannelPromise promise) {
    	// ִ�� Context��Ӧ�� handler�� invokeDisconnect����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��disconnect����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelOutboundHandler) handler()).disconnect(this, promise);
            } catch (Throwable t) {
            	// �׳��쳣
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// ��������ִ����
            disconnect(promise);
        }
    }

    @Override
    public ChannelFuture close(final ChannelPromise promise) {
    	// ��֤promise������
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        // ����(output)���͵�AbstractChannelHandlerContext����
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // ��ȡ�߳�ִ����
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// ִ��invokeClose����
            next.invokeClose(promise);
        } else {
        	// ��װ�ɶ�ʱ����
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeClose(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeClose(ChannelPromise promise) {
    	// ִ�� Context��Ӧ�� handler�� invokeClose����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��close����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelOutboundHandler) handler()).close(this, promise);
            } catch (Throwable t) {
            	// �׳��쳣
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// ��������ִ����
            close(promise);
        }
    }

    @Override
    public ChannelFuture deregister(final ChannelPromise promise) {
    	// ��֤promise������
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }
        
        // ����(output)���͵�AbstractChannelHandlerContext����
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // ��ȡ�߳�ִ����
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// ִ��invokeDeregister����
            next.invokeDeregister(promise);
        } else {
        	// ��װ�ɶ�ʱ����
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDeregister(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeDeregister(ChannelPromise promise) {
    	// ִ�� Context��Ӧ�� handler�� invokeDeregister����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��deregister����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelOutboundHandler) handler()).deregister(this, promise);
            } catch (Throwable t) {
            	// �׳��쳣
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// ��������ִ����
            deregister(promise);
        }
    }

    @Override
    public ChannelHandlerContext read() {
    	// ����(output)���͵�AbstractChannelHandlerContext����
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // ��ȡ�߳�ִ����
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// ִ��invokeRead����
            next.invokeRead();
        } else {
        	// ��װ�ɶ�ʱ����
            Runnable task = next.invokeReadTask;
            if (task == null) {
                next.invokeReadTask = task = new Runnable() {
                    @Override
                    public void run() {
                        next.invokeRead();
                    }
                };
            }
            // ��������������
            executor.execute(task);
        }

        return this;
    }

    private void invokeRead() {
    	// ִ�� Context��Ӧ�� handler�� invokeRead����
        if (invokeHandler()) {
        	// ������������ʱ
            try {
            	// ִ��handler��read����(�ڿ����׶��ɳ���Ա����дҵ�����[�ṩ������Ա�Ľӿ�])
                ((ChannelOutboundHandler) handler()).read(this);
            } catch (Throwable t) {
            	// �׳��쳣
                notifyHandlerException(t);
            }
        } else {
        	// ��������ִ����
            read();
        }
    }

    @Override
    public ChannelFuture write(Object msg) {
        return write(msg, newPromise());
    }

    @Override
    public ChannelFuture write(final Object msg, final ChannelPromise promise) {
        if (msg == null) {
            throw new NullPointerException("msg");
        }

        try {
            if (isNotValidPromise(promise, true)) {
                ReferenceCountUtil.release(msg);
                // cancelled
                return promise;
            }
        } catch (RuntimeException e) {
            ReferenceCountUtil.release(msg);
            throw e;
        }
        write(msg, false, promise);

        return promise;
    }

    private void invokeWrite(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
        } else {
            write(msg, promise);
        }
    }

    private void invokeWrite0(Object msg, ChannelPromise promise) {
        try {
            ((ChannelOutboundHandler) handler()).write(this, msg, promise);
        } catch (Throwable t) {
            notifyOutboundHandlerException(t, promise);
        }
    }

    @Override
    public ChannelHandlerContext flush() {
        final AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeFlush();
        } else {
            Runnable task = next.invokeFlushTask;
            if (task == null) {
                next.invokeFlushTask = task = new Runnable() {
                    @Override
                    public void run() {
                        next.invokeFlush();
                    }
                };
            }
            safeExecute(executor, task, channel().voidPromise(), null);
        }

        return this;
    }

    private void invokeFlush() {
        if (invokeHandler()) {
            invokeFlush0();
        } else {
            flush();
        }
    }

    private void invokeFlush0() {
        try {
            ((ChannelOutboundHandler) handler()).flush(this);
        } catch (Throwable t) {
            notifyHandlerException(t);
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        if (msg == null) {
            throw new NullPointerException("msg");
        }

        if (isNotValidPromise(promise, true)) {
            ReferenceCountUtil.release(msg);
            // cancelled
            return promise;
        }

        write(msg, true, promise);

        return promise;
    }

    private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
            invokeFlush0();
        } else {
            writeAndFlush(msg, promise);
        }
    }

    private void write(Object msg, boolean flush, ChannelPromise promise) {
        AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            if (flush) {
                next.invokeWriteAndFlush(msg, promise);
            } else {
                next.invokeWrite(msg, promise);
            }
        } else {
            AbstractWriteTask task;
            if (flush) {
                task = WriteAndFlushTask.newInstance(next, msg, promise);
            }  else {
                task = WriteTask.newInstance(next, msg, promise);
            }
            safeExecute(executor, task, promise, msg);
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return writeAndFlush(msg, newPromise());
    }

    private static void notifyOutboundHandlerException(Throwable cause, ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    private void notifyHandlerException(Throwable cause) {
        if (inExceptionCaught(cause)) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "An exception was thrown by a user handler " +
                                "while handling an exceptionCaught event", cause);
            }
            return;
        }

        invokeExceptionCaught(cause);
    }

    private static boolean inExceptionCaught(Throwable cause) {
        do {
            StackTraceElement[] trace = cause.getStackTrace();
            if (trace != null) {
                for (StackTraceElement t : trace) {
                    if (t == null) {
                        break;
                    }
                    if ("exceptionCaught".equals(t.getMethodName())) {
                        return true;
                    }
                }
            }

            cause = cause.getCause();
        } while (cause != null);

        return false;
    }

    @Override
    public ChannelPromise newPromise() {
    	// ����DefaultChannelPromise����
        return new DefaultChannelPromise(channel(), executor());
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
    	// ����DefaultChannelProgressivePromise����
        return new DefaultChannelProgressivePromise(channel(), executor());
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        ChannelFuture succeededFuture = this.succeededFuture;
        if (succeededFuture == null) {
            this.succeededFuture = succeededFuture = new SucceededChannelFuture(channel(), executor());
        }
        return succeededFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(channel(), executor(), cause);
    }

    // �ж�promise�Ƿ�voidPromise����,allowVoidPromise��ʾ�Ƿ�����
    private boolean isNotValidPromise(ChannelPromise promise, boolean allowVoidPromise) {
        // ��ָ���쳣
    	if (promise == null) {
            throw new NullPointerException("promise");
        }

    	// �ж�promise�Ƿ��Ѿ����
        if (promise.isDone()) {
            // Check if the promise was cancelled and if so signal that the processing of the operation
            // should not be performed.
            //
            // See https://github.com/netty/netty/issues/2349
            if (promise.isCancelled()) {
                return true;
            }
            throw new IllegalArgumentException("promise already done: " + promise);
        }

        // ����֮���SocketChannel�����ʱ,�׳��쳣
        if (promise.channel() != channel()) {
            throw new IllegalArgumentException(String.format(
                    "promise.channel does not match: %s (expected: %s)", promise.channel(), channel()));
        }

        // Class����ΪDefaultChannelPromiseʱ,����false
        if (promise.getClass() == DefaultChannelPromise.class) {
            return false;
        }

        // ��allowVoidPromiseΪfalseʱ,�׳��쳣
        if (!allowVoidPromise && promise instanceof VoidChannelPromise) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(VoidChannelPromise.class) + " not allowed for this operation");
        }

        // ��ΪAbstractChannel.CloseFuture����ʱ,�׳��쳣
        if (promise instanceof AbstractChannel.CloseFuture) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(AbstractChannel.CloseFuture.class) + " not allowed in a pipeline");
        }
        return false;
    }

    // ����Inbound������(Decoder)
    private AbstractChannelHandlerContext findContextInbound() {
    	// pipeline ��һ��˫���������������������ˣ�ͨ���ҵ���ǰ�ڵ����һ���ڵ㣬�����أ������жϵ��ǣ���������վ���͵�
        AbstractChannelHandlerContext ctx = this;
        do {
            ctx = ctx.next;
            // ��ΪInbound������,ѭ������
        } while (!ctx.inbound);
        return ctx;
    }

    // ����Outbound������(Encoder)
    private AbstractChannelHandlerContext findContextOutbound() {
        AbstractChannelHandlerContext ctx = this;
        do {
        	// ָ����һ��AbstractChannelHandlerContextָ��
            ctx = ctx.prev;
            // ��ΪOutbound������,ѭ������!
        } while (!ctx.outbound);
        return ctx;
    }

    @Override
    public ChannelPromise voidPromise() {
        return channel().voidPromise();
    }

    // ����ɾ�����
    final void setRemoved() {
        handlerState = REMOVE_COMPLETE;
    }

    final void setAddComplete() {
        for (;;) {
        	// ��ȡ������״̬
            int oldState = handlerState;
            // Ensure we never update when the handlerState is REMOVE_COMPLETE already.
            // oldState is usually ADD_PENDING but can also be REMOVE_COMPLETE when an EventExecutor is used that is not
            // exposing ordering guarantees.
            // ԭ���Ը���handlerState�ֶ�ֵ
            if (oldState == REMOVE_COMPLETE || HANDLER_STATE_UPDATER.compareAndSet(this, oldState, ADD_COMPLETE)) {
                return;
            }
        }
    }

    final void setAddPending() {
    	// �������������״̬��
        boolean updated = HANDLER_STATE_UPDATER.compareAndSet(this, INIT, ADD_PENDING);
        assert updated; // This should always be true as it MUST be called before setAddComplete() or setRemoved().
    }

    /**
     * Makes best possible effort to detect if {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called
     * yet. If not return {@code false} and if called or could not detect return {@code true}.
     *
     * If this method returns {@code false} we will not invoke the {@link ChannelHandler} but just forward the event.
     * This is needed as {@link DefaultChannelPipeline} may already put the {@link ChannelHandler} in the linked-list
     * but not called {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}.
     */
    private boolean invokeHandler() {
        // Store in local variable to reduce volatile reads.
    	// ����״̬
        int handlerState = this.handlerState;
        // ���鴦����״̬�Ƿ����
        return handlerState == ADD_COMPLETE || (!ordered && handlerState == ADD_PENDING);
    }

    @Override
    public boolean isRemoved() {
        return handlerState == REMOVE_COMPLETE;
    }

    private static void safeExecute(EventExecutor executor, Runnable runnable, ChannelPromise promise, Object msg) {
        try {
        	// ��������������
            executor.execute(runnable);
        } catch (Throwable cause) {
            try {
                promise.setFailure(cause);
            } finally {
                if (msg != null) {
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    abstract static class AbstractWriteTask implements Runnable {

        private static final boolean ESTIMATE_TASK_SIZE_ON_SUBMIT =
                SystemPropertyUtil.getBoolean("io.netty.transport.estimateSizeOnSubmit", true);

        // Assuming a 64-bit JVM, 16 bytes object header, 3 reference fields and one int field, plus alignment
        private static final int WRITE_TASK_OVERHEAD =
                SystemPropertyUtil.getInt("io.netty.transport.writeTaskSizeOverhead", 48);

        private final Recycler.Handle handle;
        private AbstractChannelHandlerContext ctx;
        private Object msg;
        private ChannelPromise promise;
        private int size;

        private AbstractWriteTask(Recycler.Handle handle) {
            this.handle = handle;
        }

        protected static void init(AbstractWriteTask task, AbstractChannelHandlerContext ctx,
                                   Object msg, ChannelPromise promise) {
            task.ctx = ctx;
            task.msg = msg;
            task.promise = promise;

            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                ChannelOutboundBuffer buffer = ctx.channel().unsafe().outboundBuffer();

                // Check for null as it may be set to null if the channel is closed already
                if (buffer != null) {
                    task.size = ctx.pipeline.estimatorHandle().size(msg) + WRITE_TASK_OVERHEAD;
                    buffer.incrementPendingOutboundBytes(task.size);
                } else {
                    task.size = 0;
                }
            } else {
                task.size = 0;
            }
        }

        @Override
        public final void run() {
            try {
                ChannelOutboundBuffer buffer = ctx.channel().unsafe().outboundBuffer();
                // Check for null as it may be set to null if the channel is closed already
                if (ESTIMATE_TASK_SIZE_ON_SUBMIT && buffer != null) {
                    buffer.decrementPendingOutboundBytes(size);
                }
                write(ctx, msg, promise);
            } finally {
                // Set to null so the GC can collect them directly
                ctx = null;
                msg = null;
                promise = null;
                recycle(handle);
            }
        }

        protected void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.invokeWrite(msg, promise);
        }

        protected abstract void recycle(Recycler.Handle handle);
    }

    static final class WriteTask extends AbstractWriteTask implements SingleThreadEventLoop.NonWakeupRunnable {

        private static final Recycler<WriteTask> RECYCLER = new Recycler<WriteTask>() {
            @Override
            protected WriteTask newObject(Handle handle) {
                return new WriteTask(handle);
            }
        };

        private static WriteTask newInstance(
                AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            WriteTask task = RECYCLER.get();
            init(task, ctx, msg, promise);
            return task;
        }

        private WriteTask(Recycler.Handle handle) {
            super(handle);
        }

        @Override
        protected void recycle(Recycler.Handle handle) {
            RECYCLER.recycle(this, handle);
        }
    }

    static final class WriteAndFlushTask extends AbstractWriteTask {

        private static final Recycler<WriteAndFlushTask> RECYCLER = new Recycler<WriteAndFlushTask>() {
            @Override
            protected WriteAndFlushTask newObject(Handle handle) {
                return new WriteAndFlushTask(handle);
            }
        };

        private static WriteAndFlushTask newInstance(
                AbstractChannelHandlerContext ctx, Object msg,  ChannelPromise promise) {
            WriteAndFlushTask task = RECYCLER.get();
            init(task, ctx, msg, promise);
            return task;
        }

        private WriteAndFlushTask(Recycler.Handle handle) {
            super(handle);
        }

        @Override
        public void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            super.write(ctx, msg, promise);
            ctx.invokeFlush();
        }

        @Override
        protected void recycle(Recycler.Handle handle) {
            RECYCLER.recycle(this, handle);
        }
    }
}
