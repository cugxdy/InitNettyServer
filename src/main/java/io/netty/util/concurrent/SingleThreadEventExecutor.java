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

	// 默认最大待处理任务
    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    // 标识当前的 thread的状态. 在初始的时候STATE_UPDATER == ST_NOT_STARTED.
    private static final int ST_NOT_STARTED = 1;
    // 标识当前的 thread的状态. 标识线程开启.
    private static final int ST_STARTED = 2;
    // 标识当前的 thread的状态. 标识线程正处于关闭状态中.
    private static final int ST_SHUTTING_DOWN = 3;
    // 标识当前的 thread的状态. 标识线程已处于关闭状态中.
    private static final int ST_SHUTDOWN = 4;
    // 标识当前的 thread的状态. 标识线程处于终止状态中.
    private static final int ST_TERMINATED = 5;

    private static final Runnable WAKEUP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    // 基于反射的原子更新字段的值。
    //（1）字段必须是volatile类型的，在线程之间共享变量时保证立即可见.eg:volatile int value = 3
    //（2）字段的描述类型(修饰符public/protected/default/private)是与调用者与操作对象字段的关系一致。
    // 也就是说调用者能够直接操作对象字段，那么就可以反射进行原子操作。
    // 但是对于父类的字段，子类是不能直接操作的，尽管子类可以访问父类的字段。
    //（3）只能是实例变量，不能是类变量，也就是说不能加static关键字。
    //（4）只能是可修改变量，不能使final变量，因为final的语义就是不可修改。
    // 实际上final的语义和volatile是有冲突的，这两个关键字不能同时存在。
    //（5）对于AtomicIntegerFieldUpdater和AtomicLongFieldUpdater只能修改int/long类型的字段，
    // 不能修改其包装类型(Integer/Long)。如果要修改包装类型就需要使用AtomicReferenceFieldUpdater。
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");

    private final EventExecutorGroup parent;
    
    // 用于存储任务的队列，是一个MpscUnboundedArrayQueue实例。 (多个生产者 -- 单个消费者)
    private final Queue<Runnable> taskQueue;
    
    // 存储了一个本地Java线程. 
    private final Thread thread;
    
    // 线程属性
    private final ThreadProperties threadProperties;
    
    // Semaphore是一种在多线程环境下使用的设施，该设施负责协调各个线程，
    // 以保证它们能够正确、合理的使用公共资源的设施，也是操作系统中用于控制进程同步互斥的量。
    // Semaphore是一种计数信号量，用于管理一组资源，内部是基于AQS的共享模式。
    private final Semaphore threadLock = new Semaphore(0);
    
    // shutdown时执行任务
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    
    // 是否添加WAKEUP_TASK任务
    private final boolean addTaskWakesUp;
    
    // 待处理任务个数
    private final int maxPendingTasks;
    
    // 拒绝执行的策略
    private final RejectedExecutionHandler rejectedExecutionHandler;

    // 最近执行时间
    private long lastExecutionTime;

    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED; // 线程初始化状态

    private volatile long gracefulShutdownQuietPeriod;
    
    // 优雅关闭超时时间
    private volatile long gracefulShutdownTimeout;
    // 优雅的关闭该线程操作的开始时间
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
    	// 空指针异常
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }

        this.parent = parent;
        
        // 设置成员属性addTaskWakesUp为false。
        this.addTaskWakesUp = addTaskWakesUp;

        // threadFactory.newThread 创建了一个新的 Java 线程
        thread = threadFactory.newThread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                updateLastExecutionTime();
                try {
                	// 调用run(),对selector进行轮询操作
                    SingleThreadEventExecutor.this.run();
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    for (;;) {
                    	// 获取当前线程状态
                        int oldState = STATE_UPDATER.get(SingleThreadEventExecutor.this);
                        
                        // 将state原子性的设置为ST_SHUTTING_DOWN状态
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }
                    
                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) {
                    	// 抛出错误
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
                        	// 关闭NIO的轮询队列事件
                            cleanup();
                        } finally {
                        	// 设置state状态为ST_TERMINATED状态
                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.release();
                            // 任务队列不为空时
                            if (!taskQueue.isEmpty()) {
                                logger.warn(
                                        "An event executor terminated with " +
                                        "non-empty task queue (" + taskQueue.size() + ')');
                            }

                            // 触发termination设置成功事件
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
        
        // 初始化线程属性
        threadProperties = new DefaultThreadProperties(thread);
        // 最大待处理任务个数
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        taskQueue = newTaskQueue();
        
        // 设置成员属性rejectedExecutionHandler值为RejectedExecutionHandlers.reject()方法将返回一个RejectedExecutionHandler实例。
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
    	// 建立LinkedBlockingQueue任务队列
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    @Override// 返回父类线程执行器
    public EventExecutorGroup parent() {
        return parent;
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
    	// 中断线程
        thread.interrupt();
    }

    /**
     * @see Queue#poll()
     */
    // 从任务队列删除并返回第一个元素
    protected Runnable pollTask() {
        assert inEventLoop();
        for (;;) {
        	// 从任务队列的头部返回任务
            Runnable task = taskQueue.poll();
            // 当任务为WAKEUP_TASK时,继续取出下一个任务实例
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
    // 向任务队列中取出任务
    protected Runnable takeTask() {
        assert inEventLoop();
        // 任务队列的Class不属于BlockingQueue时,抛出UnsupportedOperationException异常
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
        	// 返回定时任务队列中的头元素(返回队列第一个元素但是不删除)
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                	// take()方法是阻塞方法
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                    // 当发生中断时,直接就返回null
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
            	// 返回该定时任务等待处理时间
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                    	// 阻塞delayNanos时间并返回和删除队列中的第一个元素
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

    // 从定时任务队列中取出任务存入待执行任务队列中
    private boolean fetchFromScheduledTaskQueue() {
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        // 取出已就绪定时任务
        Runnable scheduledTask  = pollScheduledTask(nanoTime);
        // 在这里while循环是处理定时任务队列中是否存在多个已就绪的任务
        while (scheduledTask != null) {
        	// 将定时任务push任务执行队列中
            if (!taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                // 内存不足时,重新放入任务队列中
            	scheduledTaskQueue().add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
            // 取出定时任务
            scheduledTask  = pollScheduledTask(nanoTime);
        }
        return true;
    }

    /**
     * @see Queue#peek()
     */
    // 从任务队列中返回第一个元素,但是并不删除
    protected Runnable peekTask() {
        assert inEventLoop();
        // 从任务队列中取出任务
        return taskQueue.peek();
    }

    /**
     * @see Queue#isEmpty()
     */
    // 判断任务队列中是否存在任务
    protected boolean hasTasks() {
        assert inEventLoop();
        // 当任务队列为空时,返回true,当不为空时返回false
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     *
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it was care!</strong>
     */
    public int pendingTasks() {
    	// 返回任务队列长度
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    protected void addTask(Runnable task) {
    	// 空指针异常
        if (task == null) {
            throw new NullPointerException("task");
        }
        
        if (!offerTask(task)) {
        	// 调用拒绝策略
            rejectedExecutionHandler.rejected(task, this);
        }
    }

    final boolean offerTask(Runnable task) {
    	// 判断线程正处于shutdown状态中
        if (isShutdown()) {
            reject();
        }
        // 将task添加至任务队列taskQueue中
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     */
    // 从taskQueue中删除任务
    protected boolean removeTask(Runnable task) {
    	// 
        if (task == null) {
            throw new NullPointerException("task");
        }
        // 从任务队列中删除任务
        return taskQueue.remove(task);
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    // 运行任务队列中的所有任务
    protected boolean runAllTasks() {
        boolean fetchedAll;
        do {
        	// 取出定时任务队列
            fetchedAll = fetchFromScheduledTaskQueue();
            // 从任务队列中取任务
            Runnable task = pollTask();
            if (task == null) {
                return false;
            }

            for (;;) {
                try {
                	// 运行相应的任务
                    task.run();
                } catch (Throwable t) {
                    logger.warn("A task raised an exception.", t);
                }
                
                // 从任务队列中取出任务
                task = pollTask();
                // 当任务队列中不存在任务,退出循环
                if (task == null) {
                    break;
                }
            }
        // 从定时任务队列中存在定时任务
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
        // 从定时任务消息队列中弹出消息进行处理,如果消息队列为空,则退出循环
        Runnable task = pollTask();
        if (task == null) {
            return false;
        }

        // 本次调用结束的时间
        final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
        long runTasks = 0;
        long lastExecutionTime;
        for (;;) {
            try {
            	// 运行任务队列中的任务
                task.run();
            } catch (Throwable t) {
                logger.warn("A task raised an exception.", t);
            }

            // 计数器
            runTasks ++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            // 获取系统纳秒时间耗时操作,每次循环获取都获取当前系统纳秒时间会降低性能.
            // 为了提高性能,每执行60次循环判断一次,如果当前系统时间已经到了分配给非I/O操作的超时时间,则退出循环.
            if ((runTasks & 0x3F) == 0) {  
            	// 获取当前时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                // 如果已经到达死亡线时间,退出循环,这是为了防止由于非I/O任务过多导致I/O操作被长时间阻塞。
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }
            
            // 从定时任务消息队列中弹出消息进行处理,如果消息队列为空,则退出循环
            task = pollTask();
            if (task == null) {
            	// 最后执行时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }

        // 记录本次任务执行时间,以便下一次使用
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    // 计算定时任务在延迟currentTimeNanos纳秒时间后的等待时间
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
        	// 默认为1s
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
    // 更新lastExecutionTime字段
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * 由子类实现具体的线程运行代码
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    // 将WAKEUP_TASK添加至任务队列中
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop || state == ST_SHUTTING_DOWN) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
        	// 任务队列生产一个什么都不做的任务
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override // 输入参数是否与属性Thread相同
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    // 将Shutdown任务添加至shutdownHooks任务队列中
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
    // 从Shutdown任务队列中移除对应的任务
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

    // 运行Shutdown任务
    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        // 是否存在shutdown类型任务
        while (!shutdownHooks.isEmpty()) {
        	// 将set转换为List数据结构
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                	// 运行相应的任务
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
        	// 更新最新交互时间
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    @Override // 优雅的关闭
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

        // 当前运行的线程是否与该实例对象保存线程对象是否相同！
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
            	// 更改状态为ST_SHUTTING_DOWN
                newState = ST_SHUTTING_DOWN;
            } else {
            	// 处理运行Thread不一致的情况
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
            // 原子性更改字段值属性
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        // 当前实例对象的线程未运行时
        if (oldState == ST_NOT_STARTED) {
            thread.start();
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }

        // 返回Promise对象
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

    @Override// 判断是否正处于下线状态中
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override // 是否处于下线状态
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override// 判断是否处于终止状态中
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
    	// 判断线程正处的状态
        if (!isShuttingDown()) {
            return false;
        }

        // 判断当前执行线程与对象所持有线程是否一致
        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        // 撤销定时任务
        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
        	// 优雅的关闭该线程操作的开始时间
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        // 运行所有的任务以及shutdown任务
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
            	// 当前线程睡眠100ms
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

        // 尝试获取锁
        if (threadLock.tryAcquire(timeout, unit)) {
            threadLock.release();
        }

        // 判断是否为终止状态
        return isTerminated();
    }

    @Override// 执行给定的任务,如果线程一致直接添加至任务队列中
    public void execute(Runnable task) {
        if (task == null) {
        	// 抛出空指针异常
            throw new NullPointerException("task");
        }

        boolean inEventLoop = inEventLoop();
        if (inEventLoop) {
            addTask(task); // 添加至任务队列中
        } else {
        	// 启动线程
            startThread(); // 调用 startThread方法,启动EventLoop线程.
            addTask(task);
            // 判断是否正处于下线状态中
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
    	// 返回线程属性
        return threadProperties;
    }

    @SuppressWarnings("unused")
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    // 拒绝函数
    protected static void reject() {
    	// 抛出异常
        throw new RejectedExecutionException("event executor terminated");
    }

    // ScheduledExecutorService implementation
    // 1s = 1000000000ns
    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    // 启动线程
    private void startThread() {
    	// 判断线程所处的状态(未开始运行)
        if (state == ST_NOT_STARTED) {
        	// 原子性操作设置Thread运行状态为ST_STARTED
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
        	// 获取线程状态
            return t.getState();
        }

        @Override
        public int priority() {
        	// 获取优先级
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
        	// 是否为中断
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
        	// 是否为守护线程
            return t.isDaemon();
        }

        @Override
        public String name() {
        	// 线程名称
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
        	// 是否活跃状态
            return t.isAlive();
        }
    }
}
