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
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    private final EventExecutor[] children; // 事件执行组
    
    // 原子类操作(保证多线程原子性)事件执行组索引(默认构造函数时,value=0)
    private final AtomicInteger childIndex = new AtomicInteger();
    
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    
    // 创建DefaultPromise对象,GlobalEventExecutor为全局唯一的事件执行器
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    
    // EventLoop选择器
    private final EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(ThreadFactory, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        if (nThreads <= 0) {
        	// nThreads <= 0 将抛出异常 由应用程序捕获
            throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
        }

        if (threadFactory == null) {
        	// 默认的线程工厂(DefaultThreadFactory类型创建线程)
            threadFactory = newDefaultThreadFactory();
        }

        // 创建一个事件执行组
        children = new SingleThreadEventExecutor[nThreads];

        // 判断是否为2的指数(配置怎么分配给Channel分配线程的类)
        System.out.println("2的幂指数判断");
        System.out.println("children.length = " + isPowerOfTwo(children.length));
        if (isPowerOfTwo(children.length)) {
            chooser = new PowerOfTwoEventExecutorChooser();
        } else {
            chooser = new GenericEventExecutorChooser();
        }

        // 初始化线程数组
        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
            	// 创建 new NioEventLoop(创建的实际对象为NioEventLoop)
                children[i] = newChild(threadFactory, args);
                System.out.println("ClassType = " + children[i].getClass().getName());
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                if (!success) {
                    for (int j = 0; j < i; j ++) {
                    	// 优雅的关闭
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                        	// 判断state == 5是否为true
                            while (!e.isTerminated()) {
                            	// 阻塞线程等待
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                            // 在WAIT状态下,调用interrupt()方法会触发InterruptedException并将中断标志位清除
                        } catch (InterruptedException interrupted) {
                        	// 重新设置中断标志
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        // 创建监听者
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        // 为每一个单例线程池添加一个关闭监听器。
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }
    }

    // 创建新的线程生成器
    protected ThreadFactory newDefaultThreadFactory() {
    	// 创建DefaultThreadFactory实例
        return new DefaultThreadFactory(getClass());
    }

    @Override// 由具体的选择器选择Channel注册的eventLoop
    public EventExecutor next() {
        return chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return children().iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    // 返回EventLoop个数
    public final int executorCount() {
        return children.length;
    }

    /**
     * Return a safe-copy of all of the children of this group.
     */
    protected Set<EventExecutor> children() {
    	// 将children设置为set集合类
        Set<EventExecutor> children = Collections.newSetFromMap(new LinkedHashMap<EventExecutor, Boolean>());
        Collections.addAll(children, this.children);
        return children;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    protected abstract EventExecutor newChild(
            ThreadFactory threadFactory, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        // 优雅的关闭所有SocketChannel
    	for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }

    // 判断是否2的指数
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    // 具体的线程选择器
    private interface EventExecutorChooser {
        EventExecutor next();
    }

    private final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        @Override
        public EventExecutor next() {
            return children[childIndex.getAndIncrement() & children.length - 1];
        }
    }

    private final class GenericEventExecutorChooser implements EventExecutorChooser {
        @Override
        public EventExecutor next() {
            return children[Math.abs(childIndex.getAndIncrement() % children.length)];
        }
    }
}
