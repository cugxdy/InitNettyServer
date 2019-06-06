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

    private final EventExecutor[] children; // �¼�ִ����
    
    // ԭ�������(��֤���߳�ԭ����)�¼�ִ��������(Ĭ�Ϲ��캯��ʱ,value=0)
    private final AtomicInteger childIndex = new AtomicInteger();
    
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    
    // ����DefaultPromise����,GlobalEventExecutorΪȫ��Ψһ���¼�ִ����
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    
    // EventLoopѡ����
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
        	// nThreads <= 0 ���׳��쳣 ��Ӧ�ó��򲶻�
            throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
        }

        if (threadFactory == null) {
        	// Ĭ�ϵ��̹߳���(DefaultThreadFactory���ʹ����߳�)
            threadFactory = newDefaultThreadFactory();
        }

        // ����һ���¼�ִ����
        children = new SingleThreadEventExecutor[nThreads];

        // �ж��Ƿ�Ϊ2��ָ��(������ô�����Channel�����̵߳���)
        System.out.println("2����ָ���ж�");
        System.out.println("children.length = " + isPowerOfTwo(children.length));
        if (isPowerOfTwo(children.length)) {
            chooser = new PowerOfTwoEventExecutorChooser();
        } else {
            chooser = new GenericEventExecutorChooser();
        }

        // ��ʼ���߳�����
        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
            	// ���� new NioEventLoop(������ʵ�ʶ���ΪNioEventLoop)
                children[i] = newChild(threadFactory, args);
                System.out.println("ClassType = " + children[i].getClass().getName());
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                if (!success) {
                    for (int j = 0; j < i; j ++) {
                    	// ���ŵĹر�
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                        	// �ж�state == 5�Ƿ�Ϊtrue
                            while (!e.isTerminated()) {
                            	// �����̵߳ȴ�
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                            // ��WAIT״̬��,����interrupt()�����ᴥ��InterruptedException�����жϱ�־λ���
                        } catch (InterruptedException interrupted) {
                        	// ���������жϱ�־
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        // ����������
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        // Ϊÿһ�������̳߳����һ���رռ�������
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }
    }

    // �����µ��߳�������
    protected ThreadFactory newDefaultThreadFactory() {
    	// ����DefaultThreadFactoryʵ��
        return new DefaultThreadFactory(getClass());
    }

    @Override// �ɾ����ѡ����ѡ��Channelע���eventLoop
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
    // ����EventLoop����
    public final int executorCount() {
        return children.length;
    }

    /**
     * Return a safe-copy of all of the children of this group.
     */
    protected Set<EventExecutor> children() {
    	// ��children����Ϊset������
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
        // ���ŵĹر�����SocketChannel
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

    // �ж��Ƿ�2��ָ��
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    // ������߳�ѡ����
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
