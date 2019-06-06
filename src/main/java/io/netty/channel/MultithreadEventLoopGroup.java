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

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoopGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventLoopGroup extends MultithreadEventExecutorGroup implements EventLoopGroup {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MultithreadEventLoopGroup.class);

    // 默认CPU core * 2
    private static final int DEFAULT_EVENT_LOOP_THREADS;

    static {
    	// 类加载执行时
        DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));
        System.out.println("DEFAULT_EVENT_LOOP_THREADS = " + DEFAULT_EVENT_LOOP_THREADS);
        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.eventLoopThreads: {}", DEFAULT_EVENT_LOOP_THREADS);
        }
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, ThreadFactory, Object...)
     */
    // 1.默认为0    2.默认为null    3.nio provier  4.new DefaultSelectStrategyFactory单例模式 5. 默认拒绝策略:抛出异常
    protected MultithreadEventLoopGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        // io\nettyutil\concurrent\MultithreadEventExecutorGroup.java
    	super(nThreads == 0? DEFAULT_EVENT_LOOP_THREADS : nThreads, threadFactory, args);
    }

    @Override // 获取默认的线程工厂
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass(), Thread.MAX_PRIORITY);
    }

    @Override // 获取下一个EventLoop实例
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    @Override // 注册Channel
    public ChannelFuture register(Channel channel) {
        return next().register(channel);
    }

    @Override// 注册带有ChannelPromise的Channel
    public ChannelFuture register(Channel channel, ChannelPromise promise) {
        return next().register(channel, promise);
    }
}
