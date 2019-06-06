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

import io.netty.util.Signal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
	
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    
    // ������ջ���
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    
    @SuppressWarnings("rawtypes") // ԭ���Ըı�result�ֶ�ֵ
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");

    private static final Signal SUCCESS = Signal.valueOf(DefaultPromise.class.getName() + ".SUCCESS");
    
    // ���ɳ���״̬
    private static final Signal UNCANCELLABLE = Signal.valueOf(DefaultPromise.class.getName() + ".UNCANCELLABLE");
    
    // CauseHolder����
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));

    // ����ִ�н��
    private volatile Object result;
    
    // �߳�ִ����(��Channel����󶨵��̶߳���)
    private final EventExecutor executor;
    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     */
    // ��������
    private Object listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     */
    // �����̼߳�����
    private short waiters;

    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     */
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    public DefaultPromise(EventExecutor executor) {
    	// ����EventExecutor����
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    // ����������䱻����
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    @Override
    public Promise<V> setSuccess(V result) {
    	// ����setSuccess0()���������������������ж�,
    	// ��������ɹ�,�����notifyListeners����֪ͨListener
        if (setSuccess0(result)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override // ��setSuccess��������
    public boolean trySuccess(V result) {
    	// ����setSuccess0()���������������������ж�,
    	// ��������ɹ�,�����notifyListeners����֪ͨListener
        if (setSuccess0(result)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override// ����ʧ�ܽ��
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return this;
        }
        // �׳�IllegalStateException�쳣
        throw new IllegalStateException("complete already: " + this, cause);
    }

    @Override// ��setFailure����
    public boolean tryFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean setUncancellable() {
    	// ��result��������ΪUNCANCELLABLE
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        Object result = this.result;
        // ��resultΪnull��UNCANCELLABLEʱ,������true
        // ��result��ΪCauseHolderʱ,������true
        return !isDone0(result) || !isCancelled0(result);
    }

    @Override// ��this.result��ΪUNCANCELLABLEʱ���Ҳ�ΪCauseHolder����ʱ,����true
    public boolean isSuccess() {
        Object result = this.result;
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    @Override // result == nullʱ,��Ϊtrue,����ʾ��ChannelPromise����ȡ��
    public boolean isCancellable() {
        return result == null;
    }

    @Override// ����ִ�н�����쳣��Ϣ
    public Throwable cause() {
        Object result = this.result;
        return (result instanceof CauseHolder) ? ((CauseHolder) result).cause : null;
    }

    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
    	// ���listener�Ƿ�Ϊ�գ�
        checkNotNull(listener, "listener");

        // ͬ�������
        synchronized (this) {
        	// ��listener�����DefaultFutureListeners������
            addListener0(listener);
        }
        
        // �жϲ����Ƿ��Ѿ����
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        // ͬ�������
        synchronized (this) {
        	// ����listeners,������ÿ����ִ�����ɾ��
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                
                // ��listener�����DefaultFutureListeners������
                addListener0(listener);
            }
        }

        // �жϲ����Ƿ��Ѿ����
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override// ɾ��ָ����listener
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
        	// ��listener��DefaultFutureListeners�����Ƴ���
            removeListener0(listener);
        }

        return this;
    }

    @Override// ����ɾ��listeners
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        // ���listeners�Ƿ�Ϊ��
    	checkNotNull(listeners, "listeners");

        synchronized (this) {
        	// ����listeners,�����е�listenerִ��ɾ������
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                // ��listener��DefaultFutureListeners�����Ƴ���
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
    	// �첽�����Ѿ����
        if (isDone()) {
            return this;
        }

        // �߳��жϸ�λ����,ʹ���жϱ�־λΪfalse
        // true if the current thread has been interrupted
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        // �жϵ�ǰ�߳�ִ���߳��Ƿ���Channel�߳�һ�¡�
        // ���һ�µĻ��������׳�BlockingOperationException�쳣
        // �ҵ�����Ǹ�ChannelPromise�����е��̲߳������������wait����
        // ���Լ����Լ���������,��ô���I/O��������һֱ������ȥ
        checkDeadLock();

        // ͬ�������
        synchronized (this) {
        	// �жϵ�ǰPromise�����Ƿ����,����Ѿ�����ˣ�ֱ�Ӿͷ��ء�
        	// ���δ��ɣ����������ڴ�
            while (!isDone()) {
            	// ��������������
                incWaiters();
                try {
                	// ʹ�߳������ڴ�
                    wait();
                } finally {
                	// �����������ݼ�
                    decWaiters();
                }
            }
        }
        return this;
    }

    @Override// �ӳ�ִ���ж�
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        // �жϵ�ǰ�߳�ִ���߳��Ƿ���Channel�߳�һ�¡�
        // ���һ�µĻ��������׳�BlockingOperationException�쳣
        // �ҵ�����Ǹ�ChannelPromise�����е��̲߳������������wait����
        // ���Լ����Լ���������,��ô���I/O��������һֱ������ȥ
        checkDeadLock();

        boolean interrupted = false;
        
        // ͬ�������
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                // �����ж��쳣
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        // ���������ж�����ĵط�
        if (interrupted) {
        	// ���е�ǰ�߳��ж�
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    	// ����ָ����λ��ʱ��
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
    	// �������뼶ʱ��
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @SuppressWarnings("unchecked")
    @Override  // ��ȡִ�н������
    public V getNow() {
        Object result = this.result;
        // ��this.resultΪCauseHolder����this.result = SUCCESSʱ,����null
        if (result instanceof CauseHolder || result == SUCCESS) {
            return null;
        }
        // ���ؽ������
        return (V) result;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override // ����promise��������Ϊcancel����
    public boolean cancel(boolean mayInterruptIfRunning) {
    	// ����ΪCANCELLATION_CAUSE_HOLDER����(�쳣����)
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
        	// ������I/O�����������߳�
            checkNotifyWaiters();
            // ������ִ��
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override// �ж��Ƿ�ΪCancellationException�쳣��,�жϸ�Promise�Ƿ��Ѿ�������
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override// �Ƿ��Ѿ���ɲ���
    public boolean isDone() {
        return isDone0(result);
    }

    @Override// ͬ��������,�ȴ�ִ�н�������
    public Promise<V> sync() throws InterruptedException {
        await();
        
        // ���ʧ�ܵĻ��׳��쳣
        rethrowIfFailed();
        return this;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
    	// �ж���������
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override // ����String����
    public String toString() {
        return toStringBuilder().toString();
    }

    // ����StringBuilder����
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    // ��ȡexecutor�ֶ�ֵ
    protected EventExecutor executor() {
        return executor;
    }

    // �������
    protected void checkDeadLock() {
        EventExecutor e = executor();
        // �жϵ�ǰ�����߳��Ƿ����߳��ֶ�ֵ��ͬ
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        // ������������Ƿ�Ϊ��
    	checkNotNull(eventExecutor, "eventExecutor");
        checkNotNull(future, "future");
        checkNotNull(listener, "listener");
        notifyListenerWithStackOverFlowProtection(eventExecutor, future, listener);
    }

    // ���ü�����
    private void notifyListeners() {
        EventExecutor executor = executor();
        // ���²���������Channel�����̴߳���
        if (executor.inEventLoop()) {
        	// ��ȡInternalThreadLocalMap����
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            // ��ȡջ���
            final int stackDepth = threadLocals.futureListenerStackDepth();
            // ��⵱ǰ������ջ����Ƿ����
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListenersNow();
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        // ��װ������������������
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
        	
        	// ��ȡInternalThreadLocalMap����
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    // ����������
    private void notifyListenersNow() {
        Object listeners;
        // ͬ�������
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            // �����Ѿ�������listener����
            notifyingListeners = true;
            // ����listeners����
            listeners = this.listeners;
            this.listeners = null;
        }
        // ��������Զ��̲߳������listeners����ֵ
        for (;;) {
        	// �ж�this.listeners����(DefaultFutureListenersΪlisteners����)
            if (listeners instanceof DefaultFutureListeners) {
            	// ����DefaultFutureListeners�е��������
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<? extends Future<V>>) listeners);
            }
            synchronized (this) {
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                	// ����notifyingListenersΪfalse
                    notifyingListeners = false;
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
    	// ��ȡGenericFutureListener�������
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        // ����GenericFutureListener��������
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
        	// ִ��GenericFutureListener��operationComplete����
            l.operationComplete(future);
        } catch (Throwable t) {
        	// ��־��¼������Ϣ
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
        }
    }

    // ���listener
    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
    	// listenersΪ��ʱ,ֱ�Ӹ�ֵ
        if (listeners == null) {
            listeners = listener;
        // ��listenersΪDefaultFutureListeners����ʱ
        } else if (listeners instanceof DefaultFutureListeners) {
        	// ��DefaultFutureListeners��ֱ�����listener
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
        	// ����DefaultFutureListeners����(listenersΪͷ���,listenerΪβ�ڵ�)
            listeners = new DefaultFutureListeners((GenericFutureListener<? extends Future<V>>) listeners, listener);
        }
    }

    // ɾ��listener
    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
        	// ��DefaultFutureListeners��ֱ��ɾ��listener
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    private boolean setSuccess0(V result) {
    	// ���result = null-->setValue0(SUCCESS)
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
    	// ʹ��CauseHolder�������ִ�н�����쳣��Ϣ
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }

    private boolean setValue0(Object objResult) {
    	// ԭ���Ը��²������(�Ƚϲ��滻) -- unsafeʵ��
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
        	// ���������߳�
            checkNotifyWaiters();
            return true;
        }
        return false;
    }

    private synchronized void checkNotifyWaiters() {
        if (waiters > 0) {
        	// ��������ڵȴ��첽I/O������ɵ��û��̻߳�������ϵͳ�߳�,
        	// �����notifyAll���������������ڵȴ����߳�
        	// notifyAll��wait������������ͬ������ʹ��.
            notifyAll();
        }
    }
    
    // ���������߳���
    private void incWaiters() {
    	// ��Ϊ32767ʱ,�����׳�IllegalStateException�쳣
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }
    
    // ���������̼߳���
    private void decWaiters() {
        --waiters;
    }

    private void rethrowIfFailed() {
    	// ��ȡ�쳣
        Throwable cause = cause();
        if (cause == null) {
            return;
        }
        // ����javaƽ̨ʵ��
        PlatformDependent.throwException(cause);
    }

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
    	// �Ѿ����,ֱ�ӷ���
        if (isDone()) {
            return true;
        }

        // ������ʱ��С��0ʱ,ֱ�ӷ���
        if (timeoutNanos <= 0) {
            return isDone();
        }

        // �߳��Ѿ����ж�,�׳�InterruptedException�쳣,������жϱ�־λ
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }
        
        // �жϵ�ǰ�߳�ִ���߳��Ƿ���Channel�߳�һ�¡�
        // ���һ�µĻ��������׳�BlockingOperationException�쳣
        // �ҵ�����Ǹ�ChannelPromise�����е��̲߳������������wait����
        // ���Լ����Լ���������,��ô���I/O��������һֱ������ȥ
        checkDeadLock();

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        // ��ʶ�Ƿ��жϷ���
        boolean interrupted = false;
        try {
            for (;;) {
                synchronized (this) {
                	// �Ѿ����,ֱ�ӷ���
                    if (isDone()) {
                        return true;
                    }
                    // ��������������
                    incWaiters();
                    try {
                    	// ����ָ��ʱ���,�Զ������߳�
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                    	// �������ڼ�,�����ж�ʱ
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                    	// �����������ݼ�
                        decWaiters();
                    }
                }
                // �Ѿ����,ֱ�ӷ���
                if (isDone()) {
                    return true;
                } else {
                	// �ȴ�ʱ�䵽��ʱ,Ҳ��ֱ�ӷ���
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
        	// ����жϷ�����,���ж��߳�,�����ж���Ӧ
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
    	// ��ȡGenericProgressiveFutureListener�������
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
        	// �ж��Ƿ�GenericProgressiveFutureListener[]���Ͷ���
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
        	
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                // ���������װ�����������
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                // ���������װ�����������
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                    	// ����GenericProgressiveFutureListener����
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            // ��progressiveSize > 1 ʱ
            GenericFutureListener<?>[] array = dfl.listeners();
            // ����GenericProgressiveFutureListener�������
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                	// ��GenericProgressiveFutureListener�洢��copy������
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
        	// ֱ�Ӿͷ���GenericProgressiveFutureListener����
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        // ����GenericProgressiveFutureListener�������
    	for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            // ����operationProgressed�¼�
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
        	// ����operationProgressed����
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
        	// ��־��¼��
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
        }
    }

    // ��resultΪCancellationExceptionʱ,����true���жϵ�ǰPromise�Ƿ񱻳���
    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    // ��result��Ϊ���Ҳ�ΪUNCANCELLABLEʱ,����True
    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    // ��̬�ڲ���
    private static final class CauseHolder {
        final Throwable cause;
        // �����쳣��Ϣ
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
        	// �����߳�ִ������execute����
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
