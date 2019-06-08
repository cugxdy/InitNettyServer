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

import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.SystemPropertyUtil;

import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoop}'s that execute all its submitted tasks in a single thread.
 *
 */
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {

	// 默认为16待处理任务
    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE));

    /**
     * @see {@link SingleThreadEventExecutor#SingleThreadEventExecutor(EventExecutorGroup, ThreadFactory, boolean)}
     */
    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
    	// 跳转至SingleThreadEventExecutor
        super(parent, threadFactory, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
    }

    @Override // 获取父对象EventLoopGroup
    public EventLoopGroup parent() {
        return (EventLoopGroup) super.parent();
    }

    @Override // 指向下一个EventLoop对象实例
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    // this就是EventLoop(单例线程池),这里多次提到单例线程池,为什么使用单例线程池呢？
    // 一个线程还使用线程池有什么意义呢？答：需要任务队列，有很多任务需要进行调度，所以需要线程池的特性。
    // 但为了多线程的切换导致的性能损耗和为了消除同步,所以使用单个线程。
    @Override
    public ChannelFuture register(Channel channel) {
    	// 将socketChannel注册至selecer上去
        return register(channel, new DefaultChannelPromise(channel, this));
    }

    @Override
    public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
    	// 空指针异常
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        
        if (promise == null) {
            throw new NullPointerException("promise");
        }
        
        // 将socketChannel注册至selecer上去
        channel.unsafe().register(this, promise);
        return promise;
    }

    @Override// 判断该任务是否为wakesUp任务
    protected boolean wakesUpForTask(Runnable task) {
        return !(task instanceof NonWakeupRunnable);
    }

    /**
     * Marker interface for {@link Runnable} that will not trigger an {@link #wakeup(boolean)} in all cases.
     */
    interface NonWakeupRunnable extends Runnable { }
}
