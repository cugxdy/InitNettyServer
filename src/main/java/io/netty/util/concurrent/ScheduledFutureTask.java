/*
 * Copyright 2013 The Netty Project
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

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V> {
	
	// 原子性的Long类型
    private static final AtomicLong nextTaskId = new AtomicLong();
    
    // 设置定时任务开始等待的纳秒时间
    private static final long START_TIME = System.nanoTime();

    // 返回距离开始等待的时间
    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    // 计算定时任务执行的时间
    static long deadlineNanos(long delay) {
        return nanoTime() + delay;
    }

    // id = increment之前的值
    private final long id = nextTaskId.getAndIncrement();
    
    // 定时任务到达时的时间线
    private long deadlineNanos;
    
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    // 周期性任务
    private final long periodNanos;

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Runnable runnable, V result, long nanoTime) {

    	// totoCallable方法返回RunnableAdapter对象:
    	// RunnableAdapter.task = runnable;RunnableAdapter.result = result(null)
        this(executor, toCallable(runnable, result), nanoTime);
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        // 设置deadlineNanos与periodNanos属性值
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        // 设置定时任务开始执行时间
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override// 获取线程执行器
    protected EventExecutor executor() {
        return super.executor();
    }

    // 获取死亡线时间
    public long deadlineNanos() {
        return deadlineNanos;
    }

    // 获取距离死亡时间的时间
    public long delayNanos() {
    	// 定时任务死亡时间-当前时间:即为等待处理时间
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
    	// 计算在延迟currentTimeNanos纳秒时间后的等待时间
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
    	// 将等待处理时间的单位进行转换为NANOSECONDS
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override// 与延迟对象进行比较(用于优先级队列中顺序的排定)
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    @Override
    public void run() {
    	// 判断当前执行线程与对象所持有线程是否一致
        assert executor().inEventLoop();
        try {
            if (periodNanos == 0) {
            	// 设置RunnableFuture为UNCANCELLABLE状态
                if (setUncancellableInternal()) {
                	// 执行这个任务返回result
                    V result = task.call();
                    // 触发成功执行调用事件
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) {
                    task.call();
                    // 判断是否处于shutdown状态中
                    if (!executor().isShutdown()) {
                        long p = periodNanos;
                        // 当periodNanos不为零时为周期性任务
                        if (p > 0) { 
                            deadlineNanos += p;
                        } else {
                            deadlineNanos = nanoTime() - p;
                        }
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            // 获取定时任务队列,并判断定时任务队列是否为空,将周期性任务再次添加至任务队列中
                        	Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            // 判断scheduledTaskQueue是否为空
                        	assert scheduledTaskQueue != null;
                            // 将该ScheduledFutureTask对象再次添加至队列中
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
        	// 设置失败状态
            setFailureInternal(cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override // 撤销任务并移除定时任务
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
        	// 从执行任务队列中删除此定时任务
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    // 撤销任务但并不移除定时任务
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override// 创建StringBuilder对象并将其返回
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" id: ")
                  .append(id)
                  .append(", deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }
}
