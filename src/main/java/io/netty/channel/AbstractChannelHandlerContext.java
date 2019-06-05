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

	// 指向后继指针AbstractChannelHandlerContext
    volatile AbstractChannelHandlerContext next;
    
    // 指向前驱指针AbstractChannelHandlerContext
    volatile AbstractChannelHandlerContext prev;

    // 原子性更新handlerState的字段值
    private static final AtomicIntegerFieldUpdater<AbstractChannelHandlerContext> HANDLER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannelHandlerContext.class, "handlerState");

    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} is about to be called.
     */
    // 处于Add进行中
    private static final int ADD_PENDING = 1;
    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called.
     */
    // Add完成时
    private static final int ADD_COMPLETE = 2;
    /**
     * {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    // Remove完成时
    private static final int REMOVE_COMPLETE = 3;
    /**
     * Neither {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}
     * nor {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    // 初始化状态
    private static final int INIT = 0;

    // 入栈处理器
    private final boolean inbound;
    
    // 出栈处理器
    private final boolean outbound;

    // 引用DefaultChannelPipeline对象
    private final DefaultChannelPipeline pipeline;
    
    // handler名称
    private final String name;
    
    private final boolean ordered;

    // Will be set to null if no child executor should be used, otherwise it will be set to the
    // child executor.
    // 线程执行器
    final EventExecutor executor;
    private ChannelFuture succeededFuture;

    // Lazily instantiated tasks used to trigger events to a handler with different executor.
    // There is no need to make this volatile as at worse it will just create a few more instances then needed.
    private Runnable invokeChannelReadCompleteTask;
    private Runnable invokeReadTask;
    private Runnable invokeChannelWritableStateChangedTask;
    private Runnable invokeFlushTask;

    // hanlder状态
    private volatile int handlerState = INIT;

    AbstractChannelHandlerContext(DefaultChannelPipeline pipeline, EventExecutor executor, String name,
                                  boolean inbound, boolean outbound) {
    	// 设置名称
        this.name = ObjectUtil.checkNotNull(name, "name");
        // 设置DefaultChannelPipeline引用
        this.pipeline = pipeline; //赋值pipeline(private final DefaultChannelPipeline pipeline)
        // DefaultChannelPipeline 持有Channel的引用,线程执行器
        this.executor = executor;
        
        this.inbound = inbound; //入栈处理器
        this.outbound = outbound;//出栈处理器
        
        // Its ordered if its driven by the EventLoop or the given Executor is an instanceof OrderedEventExecutor.
        ordered = executor == null || executor instanceof OrderedEventExecutor;
    }

    @Override// 获取SocketChannel对象
    public Channel channel() {
        return pipeline.channel();
    }

    @Override// 获取DefaultChannelPipeline对象
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override// 获取ByteBufAllocator对象
    public ByteBufAllocator alloc() {
        return channel().config().getAllocator();
    }

    @Override// 获取线程执行器
    public EventExecutor executor() {
    	// executor为空时,获取Channel所属的eventLoop
        if (executor == null) {
            return channel().eventLoop();
        } else {
            return executor;
        }
    }

    @Override// 获取名称
    public String name() {
        return name;
    }

    @Override
    public ChannelHandlerContext fireChannelRegistered() {
    	// 执行invokeChannelRegistere静态方法(Decoder)
        invokeChannelRegistered(findContextInbound());
        return this;
    }

    static void invokeChannelRegistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        
        // 获取执行器EventLoop，用于判断是否在当前线程
        if (executor.inEventLoop()) {
        	// 在当前线程，则立即执行 invokeChannelRegistered 方法
            next.invokeChannelRegistered();
        } else { 
        	// 封装任务添加至任务队列中
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRegistered();
                }
            });
        }
    }

    private void invokeChannelRegistered() {
    	// 执行 Context对应的 handler的 channelRegistered方法
        if (invokeHandler()) {
        	// 当处理器就绪时
            try {
            	// 执行handler的channelRegistered方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).channelRegistered(this);
            } catch (Throwable t) {
            	// 当触发异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireChannelRegistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelUnregistered() {
    	// 执行invokeChannelUnregistered静态方法(Decoder)
        invokeChannelUnregistered(findContextInbound());
        return this;
    }

    static void invokeChannelUnregistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        // 获取执行器EventLoop，用于判断是否在当前线程
        if (executor.inEventLoop()) {
        	// 在当前线程，则立即执行 invokeChannelUnregistered方法
            next.invokeChannelUnregistered();
        } else {
        	// 封装任务添加至任务队列中
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelUnregistered();
                }
            });
        }
    }

    private void invokeChannelUnregistered() {
    	// 执行 Context对应的 handler的 channelUnregistered方法
        if (invokeHandler()) {
        	// 当处理器就绪时
            try {
            	// 执行handler的channelRegistered方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).channelUnregistered(this);
            } catch (Throwable t) {
            	// 当触发异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireChannelUnregistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelActive() {
    	// 执行invokeChannelActive静态方法(Decoder)
        invokeChannelActive(findContextInbound());
        return this;
    }

    static void invokeChannelActive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        // 获取执行器EventLoop，用于判断是否在当前线程
        if (executor.inEventLoop()) {
        	// 在当前线程，则立即执行 invokeChannelActive方法
            next.invokeChannelActive();
        } else {
        	// 封装任务添加至任务队列中
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelActive();
                }
            });
        }
    }

    private void invokeChannelActive() {
    	// 执行 Context对应的 handler的 invokeChannelActive方法
        if (invokeHandler()) {
        	// 当处理器就绪时
            try {
            	// 执行handler的invokeChannelActive方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).channelActive(this);
            } catch (Throwable t) {
            	// 当触发异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireChannelActive();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelInactive() {
    	// 执行invokeChannelInactive静态方法(Decoder)
        invokeChannelInactive(findContextInbound());
        return this;
    }

    static void invokeChannelInactive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        // 获取执行器EventLoop，用于判断是否在当前线程
        if (executor.inEventLoop()) {
        	// 在当前线程，则立即执行 invokeChannelInactive方法
            next.invokeChannelInactive();
        } else {
        	// 封装任务添加至任务队列中
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelInactive();
                }
            });
        }
    }

    private void invokeChannelInactive() {
    	// 执行 Context对应的 handler的 invokeChannelInactive方法
        if (invokeHandler()) {
        	// 当处理器就绪时
            try {
            	// 执行handler的channelInactive方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).channelInactive(this);
            } catch (Throwable t) {
            	// 当触发异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireChannelInactive();
        }
    }

    @Override// 执行invokeExceptionCaught静态方法
    public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
        invokeExceptionCaught(next, cause);
        return this;
    }

    static void invokeExceptionCaught(final AbstractChannelHandlerContext next, final Throwable cause) {
        // 检测cause是否为空
    	ObjectUtil.checkNotNull(cause, "cause");
        EventExecutor executor = next.executor();
     // 获取执行器EventLoop，用于判断是否在当前线程
        if (executor.inEventLoop()) {
        	// 在当前线程，则立即执行 invokeExceptionCaught方法
            next.invokeExceptionCaught(cause);
        } else {
            try {
            	// 封装成任务添加至任务队列中去
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        next.invokeExceptionCaught(cause);
                    }
                });
            } catch (Throwable t) {
            	// 记录日志信息
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to submit an exceptionCaught() event.", t);
                    logger.warn("The exceptionCaught() event that was failed to submit was:", cause);
                }
            }
        }
    }

    private void invokeExceptionCaught(final Throwable cause) {
    	// 执行 Context对应的 handler的 invokeExceptionCaught方法
        if (invokeHandler()) {
        	// 当处理器就绪时
            try {
            	// 执行handler的exceptionCaught方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
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
        	// 跳过本次执行器
            fireExceptionCaught(cause);
        }
    }

    @Override
    public ChannelHandlerContext fireUserEventTriggered(final Object event) {
    	// 执行invokeUserEventTriggered静态方法(Decoder)
        invokeUserEventTriggered(findContextInbound(), event);
        return this;
    }

    static void invokeUserEventTriggered(final AbstractChannelHandlerContext next, final Object event) {
        ObjectUtil.checkNotNull(event, "event");
        // 获取执行器EventLoop，用于判断是否在当前线程
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 在当前线程中,执行invokeUserEventTriggered方法
            next.invokeUserEventTriggered(event);
        } else {
        	// 封装成异步任务添加至任务队列中
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeUserEventTriggered(event);
                }
            });
        }
    }

    private void invokeUserEventTriggered(Object event) {
    	// 执行 Context对应的 handler的 invokeUserEventTriggered方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的userEventTriggered方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).userEventTriggered(this, event);
            } catch (Throwable t) {
            	// 当发生异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireUserEventTriggered(event);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelRead(final Object msg) {
    	// 执行invokeChannelRead静态方法(Decoder)
        invokeChannelRead(findContextInbound(), msg);
        return this;
    }
    static void invokeChannelRead(final AbstractChannelHandlerContext next, final Object msg) {
    	// 检验msg是否为空
        ObjectUtil.checkNotNull(msg, "msg");
        // 获取执行器EventLoop，用于判断是否在当前线程
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 在当前线程中,执行invokeChannelRead方法
            next.invokeChannelRead(msg);
        } else {
        	// 封装成异步任务添加至任务队列中
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRead(msg);
                }
            });
        }
    }

    private void invokeChannelRead(Object msg) {
    	// 执行 Context对应的 handler的 invokeChannelRead方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的channelRead方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).channelRead(this, msg);
            } catch (Throwable t) {
            	// 当发生异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireChannelRead(msg);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
    	// 执行invokeChannelReadComplete静态方法(Decoder)
        invokeChannelReadComplete(findContextInbound());
        return this;
    }

    static void invokeChannelReadComplete(final AbstractChannelHandlerContext next) {
    	// 获取执行器EventLoop，用于判断是否在当前线程
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 在当前线程中,执行invokeChannelReadComplete方法
            next.invokeChannelReadComplete();
        } else {
        	// 封装成异步任务添加至任务队列中
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
    	// 执行 Context对应的 handler的 invokeChannelReadComplete方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的channelReadComplete方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).channelReadComplete(this);
            } catch (Throwable t) {
            	// 当发生异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireChannelReadComplete();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelWritabilityChanged() {
    	// 执行invokeChannelWritabilityChanged静态方法(Decoder)
        invokeChannelWritabilityChanged(findContextInbound());
        return this;
    }

    static void invokeChannelWritabilityChanged(final AbstractChannelHandlerContext next) {
    	// 获取执行器EventLoop，用于判断是否在当前线程
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 在当前线程中,执行invokeChannelWritabilityChanged方法
            next.invokeChannelWritabilityChanged();
        } else {
        	// 封装成异步任务添加至任务队列中
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
    	// 执行 Context对应的 handler的 invokeChannelWritabilityChanged方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的channelWritabilityChanged方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelInboundHandler) handler()).channelWritabilityChanged(this);
            } catch (Throwable t) {
            	// 当出现异常时
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
            fireChannelWritabilityChanged();
        }
    }

    @Override// 绑定本地地址
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newPromise());
    }

    @Override// 连接远程地址
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newPromise());
    }

    @Override// 取消连接
    public ChannelFuture disconnect() {
        return disconnect(newPromise());
    }

    @Override// 关闭连接
    public ChannelFuture close() {
        return close(newPromise());
    }

    @Override// 取消注册
    public ChannelFuture deregister() {
        return deregister(newPromise());
    }

    @Override
    public ChannelFuture bind(final SocketAddress localAddress, final ChannelPromise promise) {
    	// 空指针异常
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        // 验证promise的类型
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        // 查找(output)类型的AbstractChannelHandlerContext对象
        final AbstractChannelHandlerContext next = findContextOutbound();
        // 获取线程执行器
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 执行invokeBind方法
            next.invokeBind(localAddress, promise);
        } else {
        	// 封装成定时任务
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
    	// 执行 Context对应的 handler的 invokeBind方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的bind方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelOutboundHandler) handler()).bind(this, localAddress, promise);
            } catch (Throwable t) {
            	// 抛出异常
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// 跳过本次执行器
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

    	// 空指针异常
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }
        
    	// 验证promise的类型
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }
        // 查找(output)类型的AbstractChannelHandlerContext对象
        final AbstractChannelHandlerContext next = findContextOutbound();
        // 获取线程执行器
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 执行invokeConnect方法
            next.invokeConnect(remoteAddress, localAddress, promise);
        } else {
        	// 封装成定时任务
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
    	// 执行 Context对应的 handler的 invokeConnect方法
    	if (invokeHandler()) {
    		// 当处理器存在时
            try {
            	// 执行handler的connect方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelOutboundHandler) handler()).connect(this, remoteAddress, localAddress, promise);
            } catch (Throwable t) {
            	// 抛出异常
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// 跳过本次执行器
            connect(remoteAddress, localAddress, promise);
        }
    }

    @Override
    public ChannelFuture disconnect(final ChannelPromise promise) {
    	// 验证promise的类型
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }
        
        // 查找(output)类型的AbstractChannelHandlerContext对象
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // 获取线程执行器
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            // Translate disconnect to close if the channel has no notion of disconnect-reconnect.
            // So far, UDP/IP is the only transport that has such behavior.
            if (!channel().metadata().hasDisconnect()) {
            	// 执行invokeClose方法
                next.invokeClose(promise);
            } else {
            	// 执行invokeDisconnect方法
                next.invokeDisconnect(promise);
            }
        } else {
        	// 封装成定时任务
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
    	// 执行 Context对应的 handler的 invokeDisconnect方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的disconnect方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelOutboundHandler) handler()).disconnect(this, promise);
            } catch (Throwable t) {
            	// 抛出异常
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// 跳过本次执行器
            disconnect(promise);
        }
    }

    @Override
    public ChannelFuture close(final ChannelPromise promise) {
    	// 验证promise的类型
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        // 查找(output)类型的AbstractChannelHandlerContext对象
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // 获取线程执行器
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 执行invokeClose方法
            next.invokeClose(promise);
        } else {
        	// 封装成定时任务
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
    	// 执行 Context对应的 handler的 invokeClose方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的close方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelOutboundHandler) handler()).close(this, promise);
            } catch (Throwable t) {
            	// 抛出异常
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// 跳过本次执行器
            close(promise);
        }
    }

    @Override
    public ChannelFuture deregister(final ChannelPromise promise) {
    	// 验证promise的类型
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }
        
        // 查找(output)类型的AbstractChannelHandlerContext对象
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // 获取线程执行器
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 执行invokeDeregister方法
            next.invokeDeregister(promise);
        } else {
        	// 封装成定时任务
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
    	// 执行 Context对应的 handler的 invokeDeregister方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的deregister方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelOutboundHandler) handler()).deregister(this, promise);
            } catch (Throwable t) {
            	// 抛出异常
                notifyOutboundHandlerException(t, promise);
            }
        } else {
        	// 跳过本次执行器
            deregister(promise);
        }
    }

    @Override
    public ChannelHandlerContext read() {
    	// 查找(output)类型的AbstractChannelHandlerContext对象
        final AbstractChannelHandlerContext next = findContextOutbound();
        
        // 获取线程执行器
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
        	// 执行invokeRead方法
            next.invokeRead();
        } else {
        	// 封装成定时任务
            Runnable task = next.invokeReadTask;
            if (task == null) {
                next.invokeReadTask = task = new Runnable() {
                    @Override
                    public void run() {
                        next.invokeRead();
                    }
                };
            }
            // 添加至任务队列中
            executor.execute(task);
        }

        return this;
    }

    private void invokeRead() {
    	// 执行 Context对应的 handler的 invokeRead方法
        if (invokeHandler()) {
        	// 当处理器存在时
            try {
            	// 执行handler的read方法(在开发阶段由程序员具体写业务代码[提供给程序员的接口])
                ((ChannelOutboundHandler) handler()).read(this);
            } catch (Throwable t) {
            	// 抛出异常
                notifyHandlerException(t);
            }
        } else {
        	// 跳过本次执行器
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
    	// 创建DefaultChannelPromise对象
        return new DefaultChannelPromise(channel(), executor());
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
    	// 创建DefaultChannelProgressivePromise对象
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

    // 判断promise是否voidPromise类型,allowVoidPromise表示是否允许
    private boolean isNotValidPromise(ChannelPromise promise, boolean allowVoidPromise) {
        // 空指针异常
    	if (promise == null) {
            throw new NullPointerException("promise");
        }

    	// 判断promise是否已经完成
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

        // 两者之间的SocketChannel不相等时,抛出异常
        if (promise.channel() != channel()) {
            throw new IllegalArgumentException(String.format(
                    "promise.channel does not match: %s (expected: %s)", promise.channel(), channel()));
        }

        // Class类型为DefaultChannelPromise时,返回false
        if (promise.getClass() == DefaultChannelPromise.class) {
            return false;
        }

        // 当allowVoidPromise为false时,抛出异常
        if (!allowVoidPromise && promise instanceof VoidChannelPromise) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(VoidChannelPromise.class) + " not allowed for this operation");
        }

        // 当为AbstractChannel.CloseFuture类型时,抛出异常
        if (promise instanceof AbstractChannel.CloseFuture) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(AbstractChannel.CloseFuture.class) + " not allowed in a pipeline");
        }
        return false;
    }

    // 查找Inbound处理器(Decoder)
    private AbstractChannelHandlerContext findContextInbound() {
    	// pipeline 是一个双向链表，这里链表起作用了，通过找到当前节点的下一个节点，并返回，但这判断的是：必须是入站类型的
        AbstractChannelHandlerContext ctx = this;
        do {
            ctx = ctx.next;
            // 当为Inbound处理器,循环结束
        } while (!ctx.inbound);
        return ctx;
    }

    // 查找Outbound处理器(Encoder)
    private AbstractChannelHandlerContext findContextOutbound() {
        AbstractChannelHandlerContext ctx = this;
        do {
        	// 指向下一个AbstractChannelHandlerContext指针
            ctx = ctx.prev;
            // 当为Outbound处理器,循环结束!
        } while (!ctx.outbound);
        return ctx;
    }

    @Override
    public ChannelPromise voidPromise() {
        return channel().voidPromise();
    }

    // 设置删除完成
    final void setRemoved() {
        handlerState = REMOVE_COMPLETE;
    }

    final void setAddComplete() {
        for (;;) {
        	// 获取处理器状态
            int oldState = handlerState;
            // Ensure we never update when the handlerState is REMOVE_COMPLETE already.
            // oldState is usually ADD_PENDING but can also be REMOVE_COMPLETE when an EventExecutor is used that is not
            // exposing ordering guarantees.
            // 原子性更改handlerState字段值
            if (oldState == REMOVE_COMPLETE || HANDLER_STATE_UPDATER.compareAndSet(this, oldState, ADD_COMPLETE)) {
                return;
            }
        }
    }

    final void setAddPending() {
    	// 设置正处于添加状态中
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
    	// 处理状态
        int handlerState = this.handlerState;
        // 检验处理器状态是否完成
        return handlerState == ADD_COMPLETE || (!ordered && handlerState == ADD_PENDING);
    }

    @Override
    public boolean isRemoved() {
        return handlerState == REMOVE_COMPLETE;
    }

    private static void safeExecute(EventExecutor executor, Runnable runnable, ChannelPromise promise, Object msg) {
        try {
        	// 添加至任务队列中
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
