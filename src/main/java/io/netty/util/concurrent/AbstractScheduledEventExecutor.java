/*
 * Copyright 2015 The Netty Project
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

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link EventExecutor}s that want to support scheduling.
 */
public abstract class AbstractScheduledEventExecutor extends AbstractEventExecutor {

	// (优先级)定时任务队列
    Queue<ScheduledFutureTask<?>> scheduledTaskQueue;

    // 返回距离开始等待的时间
    protected static long nanoTime() {
        return ScheduledFutureTask.nanoTime();
    }

    // 创建优先级队列用来记录系统的定时任务
    Queue<ScheduledFutureTask<?>> scheduledTaskQueue() {
        if (scheduledTaskQueue == null) {
        	// 优先级队列存储ScheduledFutureTask定时任务
            scheduledTaskQueue = new PriorityQueue<ScheduledFutureTask<?>>();
        }
        return scheduledTaskQueue;
    }

    // 判断定时任务队列是否为空
    private static  boolean isNullOrEmpty(Queue<ScheduledFutureTask<?>> queue) {
        return queue == null || queue.isEmpty();
    }

    /**
     * Cancel all scheduled tasks.
     *
     * This method MUST be called only when {@link #inEventLoop()} is {@code true}.
     */
    protected void cancelScheduledTasks() {
        assert inEventLoop(); 
        // 定时任务队列
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // 定时任务队列是否为空
        if (isNullOrEmpty(scheduledTaskQueue)) {
            return;
        }

        // 将scheduledTaskQueue优先级队列转换成数组形式
        final ScheduledFutureTask<?>[] scheduledTasks =
                scheduledTaskQueue.toArray(new ScheduledFutureTask<?>[scheduledTaskQueue.size()]);

        // 遍历定时任务数组
        for (ScheduledFutureTask<?> task: scheduledTasks) {
        	// 撤销并移除
            task.cancelWithoutRemove(false);
        }

        // 移除该队列中的所有元素
        scheduledTaskQueue.clear();
    }

    /**
     * @see #pollScheduledTask(long)
     */
    // Return the Runnable which is ready to be executed with the given nanoTime. 
    // 返回在一段时间内就绪的定时任务
    protected final Runnable pollScheduledTask() {
        return pollScheduledTask(nanoTime());
    }

    /**
     * Return the {@link Runnable} which is ready to be executed with the given {@code nanoTime}.
     * You should use {@link #nanoTime()} to retrieve the the correct {@code nanoTime}.
     */
    // 从定时任务队列中取出已经就绪的任务,从定时任务队列中取出第一个元素并返回。
    protected final Runnable pollScheduledTask(long nanoTime) {
        assert inEventLoop();

        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // the head of this queue, or null if this queue is empty
        // 查找并返回队列中的首元素
        ScheduledFutureTask<?> scheduledTask = scheduledTaskQueue == null ? null : scheduledTaskQueue.peek();
        if (scheduledTask == null) {
            return null;
        }

        // 判断当前是否已处于就绪状态
        if (scheduledTask.deadlineNanos() <= nanoTime) {
        	// 从任务队列中移除该定时任务,如果是周期任务将会再次添加进队列中
            scheduledTaskQueue.remove();
            return scheduledTask;
        }
        return null;
    }

    /**
     * Return the nanoseconds when the next scheduled task is ready to be run or {@code -1} if no task is scheduled.
     */
    // 返回下一个定时任务需要等待的时间(即从任务队列中取出第一个元素，计算还需要等待时间)
    protected final long nextScheduledTaskNano() {
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // peek:返回优先级队列中的头元素
        ScheduledFutureTask<?> scheduledTask = scheduledTaskQueue == null ? null : scheduledTaskQueue.peek();
        if (scheduledTask == null) {
            return -1;
        }
        return Math.max(0, scheduledTask.deadlineNanos() - nanoTime());
    }

    // 返回任务队列中的头元素(返回队列第一个元素但是不删除)
    final ScheduledFutureTask<?> peekScheduledTask() {
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // 是否存在定时任务队列
        if (scheduledTaskQueue == null) {
            return null;
        }
        // 查找并返回队列中的第一个元素,不改变原队列
        return scheduledTaskQueue.peek();
    }

    /**
     * Returns {@code true} if a scheduled task is ready for processing.
     */
    // 查看是否存在定时任务已经处于就绪状态(即检查队列中第一个元素是否已经到达执行时间)
    protected final boolean hasScheduledTasks() {
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        ScheduledFutureTask<?> scheduledTask = scheduledTaskQueue == null ? null : scheduledTaskQueue.peek();
        return scheduledTask != null && scheduledTask.deadlineNanos() <= nanoTime();
    }
    

    @Override// 创建定时任务并添加至优先级任务队列中
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    	// 校验输入参数字段
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        // delay当为负数时,delay = 0;
        if (delay < 0) {
            delay = 0;
        }
        // 创建ScheduledFutureTask定时任务
        return schedule(new ScheduledFutureTask<Void>(
                this, command, null, ScheduledFutureTask.deadlineNanos(unit.toNanos(delay))));
    }

    @Override// 创建定时任务并添加至优先级任务队列中
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(callable, "callable");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        
        // 创建ScheduledFutureTask定时任务
        return schedule(new ScheduledFutureTask<V>(
                this, callable, ScheduledFutureTask.deadlineNanos(unit.toNanos(delay))));
    }

    @Override// 创建周期性定时任务并添加至任务队列中
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        
        // 定时任务执行频率
        if (period <= 0) {
            throw new IllegalArgumentException(
                    String.format("period: %d (expected: > 0)", period));
        }

        // 创建ScheduledFutureTask定时任务
        return schedule(new ScheduledFutureTask<Void>(
                this, Executors.<Void>callable(command, null),
                ScheduledFutureTask.deadlineNanos(unit.toNanos(initialDelay)), unit.toNanos(period)));
    }

    @Override// 创建周期性定时任务并添加至任务队列中
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (delay <= 0) {
            throw new IllegalArgumentException(
                    String.format("delay: %d (expected: > 0)", delay));
        }

        // 创建ScheduledFutureTask定时任务
        return schedule(new ScheduledFutureTask<Void>(
                this, Executors.<Void>callable(command, null),
                ScheduledFutureTask.deadlineNanos(unit.toNanos(initialDelay)), -unit.toNanos(delay)));
    }

    <V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
    	// 判断当前执行线程与对象所持有线程是否一致
        if (inEventLoop()) {
        	// 将定时任务添加至定时任务队列中(因为存在inEventLoop()的限制,避免线程安全的问题)
            scheduledTaskQueue().add(task);
        } else {
        	// 封装成任务添加至taskQueue中
            execute(new Runnable() {
                @Override
                public void run() {
                    scheduledTaskQueue().add(task);
                }
            });
        }

        return task;
    }

    // 删除指定的定时任务
    final void removeScheduled(final ScheduledFutureTask<?> task) {
        if (inEventLoop()) {
        	// 成功移除时,返回false
            scheduledTaskQueue().remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    removeScheduled(task);
                }
            });
        }
    }
}
