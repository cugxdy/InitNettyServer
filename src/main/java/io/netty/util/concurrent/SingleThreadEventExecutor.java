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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 *
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

	// Ĭ��������������
    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    // ��ʶ��ǰ�� thread��״̬. �ڳ�ʼ��ʱ��STATE_UPDATER == ST_NOT_STARTED.
    private static final int ST_NOT_STARTED = 1;
    // ��ʶ��ǰ�� thread��״̬. ��ʶ�߳̿���.
    private static final int ST_STARTED = 2;
    // ��ʶ��ǰ�� thread��״̬. ��ʶ�߳������ڹر�״̬��.
    private static final int ST_SHUTTING_DOWN = 3;
    // ��ʶ��ǰ�� thread��״̬. ��ʶ�߳��Ѵ��ڹر�״̬��.
    private static final int ST_SHUTDOWN = 4;
    // ��ʶ��ǰ�� thread��״̬. ��ʶ�̴߳�����ֹ״̬��.
    private static final int ST_TERMINATED = 5;

    private static final Runnable WAKEUP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    // ���ڷ����ԭ�Ӹ����ֶε�ֵ��
    //��1���ֶα�����volatile���͵ģ����߳�֮�乲�����ʱ��֤�����ɼ�.eg:volatile int value = 3
    //��2���ֶε���������(���η�public/protected/default/private)�������������������ֶεĹ�ϵһ�¡�
    // Ҳ����˵�������ܹ�ֱ�Ӳ��������ֶΣ���ô�Ϳ��Է������ԭ�Ӳ�����
    // ���Ƕ��ڸ�����ֶΣ������ǲ���ֱ�Ӳ����ģ�����������Է��ʸ�����ֶΡ�
    //��3��ֻ����ʵ���������������������Ҳ����˵���ܼ�static�ؼ��֡�
    //��4��ֻ���ǿ��޸ı���������ʹfinal��������Ϊfinal��������ǲ����޸ġ�
    // ʵ����final�������volatile���г�ͻ�ģ��������ؼ��ֲ���ͬʱ���ڡ�
    //��5������AtomicIntegerFieldUpdater��AtomicLongFieldUpdaterֻ���޸�int/long���͵��ֶΣ�
    // �����޸����װ����(Integer/Long)�����Ҫ�޸İ�װ���;���Ҫʹ��AtomicReferenceFieldUpdater��
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");

    private final EventExecutorGroup parent;
    
    // ���ڴ洢����Ķ��У���һ��MpscUnboundedArrayQueueʵ���� (��������� -- ����������)
    private final Queue<Runnable> taskQueue;
    
    // �洢��һ������Java�߳�. 
    private final Thread thread;
    
    // �߳�����
    private final ThreadProperties threadProperties;
    
    // Semaphore��һ���ڶ��̻߳�����ʹ�õ���ʩ������ʩ����Э�������̣߳�
    // �Ա�֤�����ܹ���ȷ�������ʹ�ù�����Դ����ʩ��Ҳ�ǲ���ϵͳ�����ڿ��ƽ���ͬ�����������
    // Semaphore��һ�ּ����ź��������ڹ���һ����Դ���ڲ��ǻ���AQS�Ĺ���ģʽ��
    private final Semaphore threadLock = new Semaphore(0);
    
    // shutdownʱִ������
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    
    // �Ƿ����WAKEUP_TASK����
    private final boolean addTaskWakesUp;
    
    // �������������
    private final int maxPendingTasks;
    
    // �ܾ�ִ�еĲ���
    private final RejectedExecutionHandler rejectedExecutionHandler;

    // ���ִ��ʱ��
    private long lastExecutionTime;

    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED; // �̳߳�ʼ��״̬

    private volatile long gracefulShutdownQuietPeriod;
    
    // ���Źرճ�ʱʱ��
    private volatile long gracefulShutdownTimeout;
    // ���ŵĹرո��̲߳����Ŀ�ʼʱ��
    private long gracefulShutdownStartTime;

    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *  
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS,
                RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    @SuppressWarnings("deprecation")
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp, int maxPendingTasks,
            RejectedExecutionHandler rejectedHandler) {
    	// ��ָ���쳣
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }

        this.parent = parent;
        
        // ���ó�Ա����addTaskWakesUpΪfalse��
        this.addTaskWakesUp = addTaskWakesUp;

        // threadFactory.newThread ������һ���µ� Java �߳�
        thread = threadFactory.newThread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                updateLastExecutionTime();
                try {
                	// ����run(),��selector������ѯ����
                    SingleThreadEventExecutor.this.run();
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    for (;;) {
                    	// ��ȡ��ǰ�߳�״̬
                        int oldState = STATE_UPDATER.get(SingleThreadEventExecutor.this);
                        
                        // ��stateԭ���Ե�����ΪST_SHUTTING_DOWN״̬
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }
                    
                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) {
                    	// �׳�����
                        logger.error(
                                "Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must be called " +
                                "before run() implementation terminates.");
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks.
                        for (;;) {
                            if (confirmShutdown()) {
                                break;
                            }
                        }
                    } finally {
                        try {
                        	// �ر�NIO����ѯ�����¼�
                            cleanup();
                        } finally {
                        	// ����state״̬ΪST_TERMINATED״̬
                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.release();
                            // ������в�Ϊ��ʱ
                            if (!taskQueue.isEmpty()) {
                                logger.warn(
                                        "An event executor terminated with " +
                                        "non-empty task queue (" + taskQueue.size() + ')');
                            }

                            // ����termination���óɹ��¼�
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
        
        // ��ʼ���߳�����
        threadProperties = new DefaultThreadProperties(thread);
        // ���������������
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        taskQueue = newTaskQueue();
        
        // ���ó�Ա����rejectedExecutionHandlerֵΪRejectedExecutionHandlers.reject()����������һ��RejectedExecutionHandlerʵ����
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
    	// ����LinkedBlockingQueue�������
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    @Override// ���ظ����߳�ִ����
    public EventExecutorGroup parent() {
        return parent;
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
    	// �ж��߳�
        thread.interrupt();
    }

    /**
     * @see Queue#poll()
     */
    // ���������ɾ�������ص�һ��Ԫ��
    protected Runnable pollTask() {
        assert inEventLoop();
        for (;;) {
        	// ��������е�ͷ����������
            Runnable task = taskQueue.poll();
            // ������ΪWAKEUP_TASKʱ,����ȡ����һ������ʵ��
            if (task == WAKEUP_TASK) {
                continue;
            }
            return task;
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    // �����������ȡ������
    protected Runnable takeTask() {
        assert inEventLoop();
        // ������е�Class������BlockingQueueʱ,�׳�UnsupportedOperationException�쳣
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
        	// ���ض�ʱ��������е�ͷԪ��(���ض��е�һ��Ԫ�ص��ǲ�ɾ��)
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                	// take()��������������
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                    // �������ж�ʱ,ֱ�Ӿͷ���null
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
            	// ���ظö�ʱ����ȴ�����ʱ��
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                    	// ����delayNanosʱ�䲢���غ�ɾ�������еĵ�һ��Ԫ��
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    // �Ӷ�ʱ���������ȡ����������ִ�����������
    private boolean fetchFromScheduledTaskQueue() {
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        // ȡ���Ѿ�����ʱ����
        Runnable scheduledTask  = pollScheduledTask(nanoTime);
        // ������whileѭ���Ǵ���ʱ����������Ƿ���ڶ���Ѿ���������
        while (scheduledTask != null) {
        	// ����ʱ����push����ִ�ж�����
            if (!taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                // �ڴ治��ʱ,���·������������
            	scheduledTaskQueue().add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
            // ȡ����ʱ����
            scheduledTask  = pollScheduledTask(nanoTime);
        }
        return true;
    }

    /**
     * @see Queue#peek()
     */
    // ����������з��ص�һ��Ԫ��,���ǲ���ɾ��
    protected Runnable peekTask() {
        assert inEventLoop();
        // �����������ȡ������
        return taskQueue.peek();
    }

    /**
     * @see Queue#isEmpty()
     */
    // �ж�����������Ƿ��������
    protected boolean hasTasks() {
        assert inEventLoop();
        // ���������Ϊ��ʱ,����true,����Ϊ��ʱ����false
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     *
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it was care!</strong>
     */
    public int pendingTasks() {
    	// ����������г���
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    protected void addTask(Runnable task) {
    	// ��ָ���쳣
        if (task == null) {
            throw new NullPointerException("task");
        }
        
        if (!offerTask(task)) {
        	// ���þܾ�����
            rejectedExecutionHandler.rejected(task, this);
        }
    }

    final boolean offerTask(Runnable task) {
    	// �ж��߳�������shutdown״̬��
        if (isShutdown()) {
            reject();
        }
        // ��task������������taskQueue��
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     */
    // ��taskQueue��ɾ������
    protected boolean removeTask(Runnable task) {
    	// 
        if (task == null) {
            throw new NullPointerException("task");
        }
        // �����������ɾ������
        return taskQueue.remove(task);
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    // ������������е���������
    protected boolean runAllTasks() {
        boolean fetchedAll;
        do {
        	// ȡ����ʱ�������
            fetchedAll = fetchFromScheduledTaskQueue();
            // �����������ȡ����
            Runnable task = pollTask();
            if (task == null) {
                return false;
            }

            for (;;) {
                try {
                	// ������Ӧ������
                    task.run();
                } catch (Throwable t) {
                    logger.warn("A task raised an exception.", t);
                }
                
                // �����������ȡ������
                task = pollTask();
                // ����������в���������,�˳�ѭ��
                if (task == null) {
                    break;
                }
            }
        // �Ӷ�ʱ��������д��ڶ�ʱ����
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        lastExecutionTime = ScheduledFutureTask.nanoTime();
        return true;
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     */
    protected boolean runAllTasks(long timeoutNanos) {
        fetchFromScheduledTaskQueue();
        // �Ӷ�ʱ������Ϣ�����е�����Ϣ���д���,�����Ϣ����Ϊ��,���˳�ѭ��
        Runnable task = pollTask();
        if (task == null) {
            return false;
        }

        // ���ε��ý�����ʱ��
        final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
        long runTasks = 0;
        long lastExecutionTime;
        for (;;) {
            try {
            	// ������������е�����
                task.run();
            } catch (Throwable t) {
                logger.warn("A task raised an exception.", t);
            }

            // ������
            runTasks ++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            // ��ȡϵͳ����ʱ���ʱ����,ÿ��ѭ����ȡ����ȡ��ǰϵͳ����ʱ��ή������.
            // Ϊ���������,ÿִ��60��ѭ���ж�һ��,�����ǰϵͳʱ���Ѿ����˷������I/O�����ĳ�ʱʱ��,���˳�ѭ��.
            if ((runTasks & 0x3F) == 0) {  
            	// ��ȡ��ǰʱ��
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                // ����Ѿ�����������ʱ��,�˳�ѭ��,����Ϊ�˷�ֹ���ڷ�I/O������ർ��I/O��������ʱ��������
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }
            
            // �Ӷ�ʱ������Ϣ�����е�����Ϣ���д���,�����Ϣ����Ϊ��,���˳�ѭ��
            task = pollTask();
            if (task == null) {
            	// ���ִ��ʱ��
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }

        // ��¼��������ִ��ʱ��,�Ա���һ��ʹ��
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    // ���㶨ʱ�������ӳ�currentTimeNanos����ʱ���ĵȴ�ʱ��
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
        	// Ĭ��Ϊ1s
            return SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    // ����lastExecutionTime�ֶ�
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * ������ʵ�־�����߳����д���
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    // ��WAKEUP_TASK��������������
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop || state == ST_SHUTTING_DOWN) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
        	// �����������һ��ʲô������������
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override // ��������Ƿ�������Thread��ͬ
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    // ��Shutdown���������shutdownHooks���������
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    // ��Shutdown����������Ƴ���Ӧ������
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    // ����Shutdown����
    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        // �Ƿ����shutdown��������
        while (!shutdownHooks.isEmpty()) {
        	// ��setת��ΪList���ݽṹ
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                	// ������Ӧ������
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
        	// �������½���ʱ��
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    @Override // ���ŵĹر�
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        if (quietPeriod < 0) {
            throw new IllegalArgumentException("quietPeriod: " + quietPeriod + " (expected >= 0)");
        }
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (isShuttingDown()) {
            return terminationFuture();
        }

        // ��ǰ���е��߳��Ƿ����ʵ�����󱣴��̶߳����Ƿ���ͬ��
        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        
        for (;;) {
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
            	// ����״̬ΪST_SHUTTING_DOWN
                newState = ST_SHUTTING_DOWN;
            } else {
            	// ��������Thread��һ�µ����
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            // ԭ���Ը����ֶ�ֵ����
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        // ��ǰʵ��������߳�δ����ʱ
        if (oldState == ST_NOT_STARTED) {
            thread.start();
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }

        // ����Promise����
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (oldState == ST_NOT_STARTED) {
            thread.start();
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }
    }

    @Override// �ж��Ƿ�����������״̬��
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override // �Ƿ�������״̬
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override// �ж��Ƿ�����ֹ״̬��
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
    	// �ж��߳�������״̬
        if (!isShuttingDown()) {
            return false;
        }

        // �жϵ�ǰִ���߳�������������߳��Ƿ�һ��
        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        // ������ʱ����
        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
        	// ���ŵĹرո��̲߳����Ŀ�ʼʱ��
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        // �������е������Լ�shutdown����
        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            
            wakeup(true);
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            wakeup(true);
            try {
            	// ��ǰ�߳�˯��100ms
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        // ���Ի�ȡ��
        if (threadLock.tryAcquire(timeout, unit)) {
            threadLock.release();
        }

        // �ж��Ƿ�Ϊ��ֹ״̬
        return isTerminated();
    }

    @Override// ִ�и���������,����߳�һ��ֱ����������������
    public void execute(Runnable task) {
        if (task == null) {
        	// �׳���ָ���쳣
            throw new NullPointerException("task");
        }

        boolean inEventLoop = inEventLoop();
        if (inEventLoop) {
            addTask(task); // ��������������
        } else {
        	// �����߳�
            startThread(); // ���� startThread����,����EventLoop�߳�.
            addTask(task);
            // �ж��Ƿ�����������״̬��
            if (isShutdown() && removeTask(task)) {
                reject();
            }
        }

        if (!addTaskWakesUp && wakesUpForTask(task)) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     */
    public final ThreadProperties threadProperties() {
    	// �����߳�����
        return threadProperties;
    }

    @SuppressWarnings("unused")
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    // �ܾ�����
    protected static void reject() {
    	// �׳��쳣
        throw new RejectedExecutionException("event executor terminated");
    }

    // ScheduledExecutorService implementation
    // 1s = 1000000000ns
    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    // �����߳�
    private void startThread() {
    	// �ж��߳�������״̬(δ��ʼ����)
        if (state == ST_NOT_STARTED) {
        	// ԭ���Բ�������Thread����״̬ΪST_STARTED
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                thread.start();
            }
        }
    }

    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
        	// ��ȡ�߳�״̬
            return t.getState();
        }

        @Override
        public int priority() {
        	// ��ȡ���ȼ�
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
        	// �Ƿ�Ϊ�ж�
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
        	// �Ƿ�Ϊ�ػ��߳�
            return t.isDaemon();
        }

        @Override
        public String name() {
        	// �߳�����
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
        	// �Ƿ��Ծ״̬
            return t.isAlive();
        }
    }
}
