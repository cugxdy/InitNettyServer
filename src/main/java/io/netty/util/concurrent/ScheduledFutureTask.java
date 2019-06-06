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
	
	// ԭ���Ե�Long����
    private static final AtomicLong nextTaskId = new AtomicLong();
    
    // ���ö�ʱ����ʼ�ȴ�������ʱ��
    private static final long START_TIME = System.nanoTime();

    // ���ؾ��뿪ʼ�ȴ���ʱ��
    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    // ���㶨ʱ����ִ�е�ʱ��
    static long deadlineNanos(long delay) {
        return nanoTime() + delay;
    }

    // id = increment֮ǰ��ֵ
    private final long id = nextTaskId.getAndIncrement();
    
    // ��ʱ���񵽴�ʱ��ʱ����
    private long deadlineNanos;
    
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    // ����������
    private final long periodNanos;

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Runnable runnable, V result, long nanoTime) {

    	// totoCallable��������RunnableAdapter����:
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
        // ����deadlineNanos��periodNanos����ֵ
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        // ���ö�ʱ����ʼִ��ʱ��
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override// ��ȡ�߳�ִ����
    protected EventExecutor executor() {
        return super.executor();
    }

    // ��ȡ������ʱ��
    public long deadlineNanos() {
        return deadlineNanos;
    }

    // ��ȡ��������ʱ���ʱ��
    public long delayNanos() {
    	// ��ʱ��������ʱ��-��ǰʱ��:��Ϊ�ȴ�����ʱ��
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
    	// �������ӳ�currentTimeNanos����ʱ���ĵȴ�ʱ��
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
    	// ���ȴ�����ʱ��ĵ�λ����ת��ΪNANOSECONDS
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override// ���ӳٶ�����бȽ�(�������ȼ�������˳����Ŷ�)
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
    	// �жϵ�ǰִ���߳�������������߳��Ƿ�һ��
        assert executor().inEventLoop();
        try {
            if (periodNanos == 0) {
            	// ����RunnableFutureΪUNCANCELLABLE״̬
                if (setUncancellableInternal()) {
                	// ִ��������񷵻�result
                    V result = task.call();
                    // �����ɹ�ִ�е����¼�
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) {
                    task.call();
                    // �ж��Ƿ���shutdown״̬��
                    if (!executor().isShutdown()) {
                        long p = periodNanos;
                        // ��periodNanos��Ϊ��ʱΪ����������
                        if (p > 0) { 
                            deadlineNanos += p;
                        } else {
                            deadlineNanos = nanoTime() - p;
                        }
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            // ��ȡ��ʱ�������,���ж϶�ʱ��������Ƿ�Ϊ��,�������������ٴ���������������
                        	Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            // �ж�scheduledTaskQueue�Ƿ�Ϊ��
                        	assert scheduledTaskQueue != null;
                            // ����ScheduledFutureTask�����ٴ������������
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
        	// ����ʧ��״̬
            setFailureInternal(cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override // ���������Ƴ���ʱ����
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
        	// ��ִ�����������ɾ���˶�ʱ����
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    // �������񵫲����Ƴ���ʱ����
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override// ����StringBuilder���󲢽��䷵��
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
