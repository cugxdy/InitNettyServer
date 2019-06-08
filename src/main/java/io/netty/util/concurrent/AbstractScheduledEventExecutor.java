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

	// (���ȼ�)��ʱ�������
    Queue<ScheduledFutureTask<?>> scheduledTaskQueue;

    // ���ؾ��뿪ʼ�ȴ���ʱ��
    protected static long nanoTime() {
        return ScheduledFutureTask.nanoTime();
    }

    // �������ȼ�����������¼ϵͳ�Ķ�ʱ����
    Queue<ScheduledFutureTask<?>> scheduledTaskQueue() {
        if (scheduledTaskQueue == null) {
        	// ���ȼ����д洢ScheduledFutureTask��ʱ����
            scheduledTaskQueue = new PriorityQueue<ScheduledFutureTask<?>>();
        }
        return scheduledTaskQueue;
    }

    // �ж϶�ʱ��������Ƿ�Ϊ��
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
        // ��ʱ�������
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // ��ʱ��������Ƿ�Ϊ��
        if (isNullOrEmpty(scheduledTaskQueue)) {
            return;
        }

        // ��scheduledTaskQueue���ȼ�����ת����������ʽ
        final ScheduledFutureTask<?>[] scheduledTasks =
                scheduledTaskQueue.toArray(new ScheduledFutureTask<?>[scheduledTaskQueue.size()]);

        // ������ʱ��������
        for (ScheduledFutureTask<?> task: scheduledTasks) {
        	// �������Ƴ�
            task.cancelWithoutRemove(false);
        }

        // �Ƴ��ö����е�����Ԫ��
        scheduledTaskQueue.clear();
    }

    /**
     * @see #pollScheduledTask(long)
     */
    // Return the Runnable which is ready to be executed with the given nanoTime. 
    // ������һ��ʱ���ھ����Ķ�ʱ����
    protected final Runnable pollScheduledTask() {
        return pollScheduledTask(nanoTime());
    }

    /**
     * Return the {@link Runnable} which is ready to be executed with the given {@code nanoTime}.
     * You should use {@link #nanoTime()} to retrieve the the correct {@code nanoTime}.
     */
    // �Ӷ�ʱ���������ȡ���Ѿ�����������,�Ӷ�ʱ���������ȡ����һ��Ԫ�ز����ء�
    protected final Runnable pollScheduledTask(long nanoTime) {
        assert inEventLoop();

        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // the head of this queue, or null if this queue is empty
        // ���Ҳ����ض����е���Ԫ��
        ScheduledFutureTask<?> scheduledTask = scheduledTaskQueue == null ? null : scheduledTaskQueue.peek();
        if (scheduledTask == null) {
            return null;
        }

        // �жϵ�ǰ�Ƿ��Ѵ��ھ���״̬
        if (scheduledTask.deadlineNanos() <= nanoTime) {
        	// ������������Ƴ��ö�ʱ����,������������񽫻��ٴ���ӽ�������
            scheduledTaskQueue.remove();
            return scheduledTask;
        }
        return null;
    }

    /**
     * Return the nanoseconds when the next scheduled task is ready to be run or {@code -1} if no task is scheduled.
     */
    // ������һ����ʱ������Ҫ�ȴ���ʱ��(�������������ȡ����һ��Ԫ�أ����㻹��Ҫ�ȴ�ʱ��)
    protected final long nextScheduledTaskNano() {
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // peek:�������ȼ������е�ͷԪ��
        ScheduledFutureTask<?> scheduledTask = scheduledTaskQueue == null ? null : scheduledTaskQueue.peek();
        if (scheduledTask == null) {
            return -1;
        }
        return Math.max(0, scheduledTask.deadlineNanos() - nanoTime());
    }

    // ������������е�ͷԪ��(���ض��е�һ��Ԫ�ص��ǲ�ɾ��)
    final ScheduledFutureTask<?> peekScheduledTask() {
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        // �Ƿ���ڶ�ʱ�������
        if (scheduledTaskQueue == null) {
            return null;
        }
        // ���Ҳ����ض����еĵ�һ��Ԫ��,���ı�ԭ����
        return scheduledTaskQueue.peek();
    }

    /**
     * Returns {@code true} if a scheduled task is ready for processing.
     */
    // �鿴�Ƿ���ڶ�ʱ�����Ѿ����ھ���״̬(���������е�һ��Ԫ���Ƿ��Ѿ�����ִ��ʱ��)
    protected final boolean hasScheduledTasks() {
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        ScheduledFutureTask<?> scheduledTask = scheduledTaskQueue == null ? null : scheduledTaskQueue.peek();
        return scheduledTask != null && scheduledTask.deadlineNanos() <= nanoTime();
    }
    

    @Override// ������ʱ������������ȼ����������
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    	// У����������ֶ�
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        // delay��Ϊ����ʱ,delay = 0;
        if (delay < 0) {
            delay = 0;
        }
        // ����ScheduledFutureTask��ʱ����
        return schedule(new ScheduledFutureTask<Void>(
                this, command, null, ScheduledFutureTask.deadlineNanos(unit.toNanos(delay))));
    }

    @Override// ������ʱ������������ȼ����������
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(callable, "callable");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        
        // ����ScheduledFutureTask��ʱ����
        return schedule(new ScheduledFutureTask<V>(
                this, callable, ScheduledFutureTask.deadlineNanos(unit.toNanos(delay))));
    }

    @Override// ���������Զ�ʱ������������������
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        
        // ��ʱ����ִ��Ƶ��
        if (period <= 0) {
            throw new IllegalArgumentException(
                    String.format("period: %d (expected: > 0)", period));
        }

        // ����ScheduledFutureTask��ʱ����
        return schedule(new ScheduledFutureTask<Void>(
                this, Executors.<Void>callable(command, null),
                ScheduledFutureTask.deadlineNanos(unit.toNanos(initialDelay)), unit.toNanos(period)));
    }

    @Override// ���������Զ�ʱ������������������
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

        // ����ScheduledFutureTask��ʱ����
        return schedule(new ScheduledFutureTask<Void>(
                this, Executors.<Void>callable(command, null),
                ScheduledFutureTask.deadlineNanos(unit.toNanos(initialDelay)), -unit.toNanos(delay)));
    }

    <V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
    	// �жϵ�ǰִ���߳�������������߳��Ƿ�һ��
        if (inEventLoop()) {
        	// ����ʱ�����������ʱ���������(��Ϊ����inEventLoop()������,�����̰߳�ȫ������)
            scheduledTaskQueue().add(task);
        } else {
        	// ��װ�����������taskQueue��
            execute(new Runnable() {
                @Override
                public void run() {
                    scheduledTaskQueue().add(task);
                }
            });
        }

        return task;
    }

    // ɾ��ָ���Ķ�ʱ����
    final void removeScheduled(final ScheduledFutureTask<?> task) {
        if (inEventLoop()) {
        	// �ɹ��Ƴ�ʱ,����false
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
