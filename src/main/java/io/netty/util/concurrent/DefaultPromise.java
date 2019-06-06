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
    
    // 最大监听栈深度
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    
    @SuppressWarnings("rawtypes") // 原子性改变result字段值
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");

    private static final Signal SUCCESS = Signal.valueOf(DefaultPromise.class.getName() + ".SUCCESS");
    
    // 不可撤销状态
    private static final Signal UNCANCELLABLE = Signal.valueOf(DefaultPromise.class.getName() + ".UNCANCELLABLE");
    
    // CauseHolder对象
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));

    // 任务执行结果
    private volatile Object result;
    
    // 线程执行器(与Channel对象绑定的线程对象)
    private final EventExecutor executor;
    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     */
    // 监听对象
    private Object listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     */
    // 阻塞线程计数器
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
    	// 设置EventExecutor对象
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    // 仅仅在子类间被调用
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    @Override
    public Promise<V> setSuccess(V result) {
    	// 调用setSuccess0()方法并对其操作结果进行判断,
    	// 如果操作成功,则调用notifyListeners方法通知Listener
        if (setSuccess0(result)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override // 与setSuccess操作类似
    public boolean trySuccess(V result) {
    	// 调用setSuccess0()方法并对其操作结果进行判断,
    	// 如果操作成功,则调用notifyListeners方法通知Listener
        if (setSuccess0(result)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override// 设置失败结果
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return this;
        }
        // 抛出IllegalStateException异常
        throw new IllegalStateException("complete already: " + this, cause);
    }

    @Override// 与setFailure类似
    public boolean tryFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean setUncancellable() {
    	// 将result属性设置为UNCANCELLABLE
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        Object result = this.result;
        // 当result为null或UNCANCELLABLE时,即返回true
        // 当result不为CauseHolder时,即返回true
        return !isDone0(result) || !isCancelled0(result);
    }

    @Override// 当this.result不为UNCANCELLABLE时而且不为CauseHolder类型时,返回true
    public boolean isSuccess() {
        Object result = this.result;
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    @Override // result == null时,即为true,即表示该ChannelPromise允许被取消
    public boolean isCancellable() {
        return result == null;
    }

    @Override// 返回执行结果的异常信息
    public Throwable cause() {
        Object result = this.result;
        return (result instanceof CauseHolder) ? ((CauseHolder) result).cause : null;
    }

    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
    	// 检测listener是否为空！
        checkNotNull(listener, "listener");

        // 同步代码块
        synchronized (this) {
        	// 将listener添加至DefaultFutureListeners对象中
            addListener0(listener);
        }
        
        // 判断操作是否已经完成
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        // 同步代码块
        synchronized (this) {
        	// 遍历listeners,对其中每个都执行添加删除
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                
                // 将listener添加至DefaultFutureListeners对象中
                addListener0(listener);
            }
        }

        // 判断操作是否已经完成
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override// 删除指定的listener
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
        	// 将listener从DefaultFutureListeners对象移除中
            removeListener0(listener);
        }

        return this;
    }

    @Override// 批量删除listeners
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        // 检测listeners是否为空
    	checkNotNull(listeners, "listeners");

        synchronized (this) {
        	// 遍历listeners,对其中的listener执行删除操作
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                // 将listener从DefaultFutureListeners对象移除中
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
    	// 异步操作已经完成
        if (isDone()) {
            return this;
        }

        // 线程中断复位操作,使得中断标志位为false
        // true if the current thread has been interrupted
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        // 判断当前线程执行线程是否与Channel线程一致。
        // 如果一致的话，将会抛出BlockingOperationException异常
        // 我的理解是该ChannelPromise所持有的线程不允许调用自身wait方法
        // 即自己将自己给阻塞了,那么这个I/O操作将会一直阻塞下去
        checkDeadLock();

        // 同步代码块
        synchronized (this) {
        	// 判断当前Promise动作是否完成,如果已经完成了，直接就返回。
        	// 如果未完成，将会阻塞在此
            while (!isDone()) {
            	// 阻塞计数器递增
                incWaiters();
                try {
                	// 使线程阻塞在此
                    wait();
                } finally {
                	// 阻塞计数器递减
                    decWaiters();
                }
            }
        }
        return this;
    }

    @Override// 延迟执行中断
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        // 判断当前线程执行线程是否与Channel线程一致。
        // 如果一致的话，将会抛出BlockingOperationException异常
        // 我的理解是该ChannelPromise所持有的线程不允许调用自身wait方法
        // 即自己将自己给阻塞了,那么这个I/O操作将会一直阻塞下去
        checkDeadLock();

        boolean interrupted = false;
        
        // 同步代码块
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                // 捕获中断异常
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        // 真正处理中断请求的地方
        if (interrupted) {
        	// 进行当前线程中断
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    	// 阻塞指定单位的时间
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
    	// 阻塞毫秒级时间
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
    @Override  // 获取执行结果对象
    public V getNow() {
        Object result = this.result;
        // 当this.result为CauseHolder或者this.result = SUCCESS时,返回null
        if (result instanceof CauseHolder || result == SUCCESS) {
            return null;
        }
        // 返回结果对象
        return (V) result;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override // 将该promise对象设置为cancel对象
    public boolean cancel(boolean mayInterruptIfRunning) {
    	// 设置为CANCELLATION_CAUSE_HOLDER对象(异常对象)
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
        	// 唤醒因I/O操作阻塞的线程
            checkNotifyWaiters();
            // 监听器执行
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override// 判断是否为CancellationException异常类,判断该Promise是否已经被撤销
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override// 是否已经完成操作
    public boolean isDone() {
        return isDone0(result);
    }

    @Override// 同步阻塞块,等待执行结果的完成
    public Promise<V> sync() throws InterruptedException {
        await();
        
        // 如果失败的话抛出异常
        rethrowIfFailed();
        return this;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
    	// 中断阻塞进程
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override // 返回String对象
    public String toString() {
        return toStringBuilder().toString();
    }

    // 返回StringBuilder对象
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
    // 获取executor字段值
    protected EventExecutor executor() {
        return executor;
    }

    // 检测死锁
    protected void checkDeadLock() {
        EventExecutor e = executor();
        // 判断当前运行线程是否与线程字段值相同
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
        // 检验输入参数是否为空
    	checkNotNull(eventExecutor, "eventExecutor");
        checkNotNull(future, "future");
        checkNotNull(listener, "listener");
        notifyListenerWithStackOverFlowProtection(eventExecutor, future, listener);
    }

    // 调用监听者
    private void notifyListeners() {
        EventExecutor executor = executor();
        // 以下操作必须由Channel所绑定线程触发
        if (executor.inEventLoop()) {
        	// 获取InternalThreadLocalMap对象
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            // 获取栈深度
            final int stackDepth = threadLocals.futureListenerStackDepth();
            // 检测当前监听器栈深度是否溢出
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

        // 封装任务添加至任务队列中
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
        	
        	// 获取InternalThreadLocalMap对象
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

    // 触发监听者
    private void notifyListenersNow() {
        Object listeners;
        // 同步代码块
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            // 设置已经触发过listener对象
            notifyingListeners = true;
            // 设置listeners变量
            listeners = this.listeners;
            this.listeners = null;
        }
        // 在这个可以多线程操作这个listeners属性值
        for (;;) {
        	// 判断this.listeners类型(DefaultFutureListeners为listeners容器)
            if (listeners instanceof DefaultFutureListeners) {
            	// 遍历DefaultFutureListeners中的数组对象
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<? extends Future<V>>) listeners);
            }
            synchronized (this) {
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                	// 设置notifyingListeners为false
                    notifyingListeners = false;
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
    	// 获取GenericFutureListener数组对象
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        // 遍历GenericFutureListener类型数组
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
        	// 执行GenericFutureListener的operationComplete方法
            l.operationComplete(future);
        } catch (Throwable t) {
        	// 日志记录警告信息
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
        }
    }

    // 添加listener
    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
    	// listeners为空时,直接赋值
        if (listeners == null) {
            listeners = listener;
        // 当listeners为DefaultFutureListeners类型时
        } else if (listeners instanceof DefaultFutureListeners) {
        	// 在DefaultFutureListeners中直接添加listener
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
        	// 创建DefaultFutureListeners对象(listeners为头结点,listener为尾节点)
            listeners = new DefaultFutureListeners((GenericFutureListener<? extends Future<V>>) listeners, listener);
        }
    }

    // 删除listener
    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
        	// 在DefaultFutureListeners中直接删除listener
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    private boolean setSuccess0(V result) {
    	// 如果result = null-->setValue0(SUCCESS)
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
    	// 使用CauseHolder对象持有执行结果的异常信息
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }

    private boolean setValue0(Object objResult) {
    	// 原子性更新操作结果(比较并替换) -- unsafe实现
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
        	// 唤醒阻塞线程
            checkNotifyWaiters();
            return true;
        }
        return false;
    }

    private synchronized void checkNotifyWaiters() {
        if (waiters > 0) {
        	// 如果有正在等待异步I/O操作完成的用户线程或者其他系统线程,
        	// 则调用notifyAll方法唤醒所有正在等待的线程
        	// notifyAll和wait方法都必须在同步块内使用.
            notifyAll();
        }
    }
    
    // 增加阻塞线程数
    private void incWaiters() {
    	// 当为32767时,将会抛出IllegalStateException异常
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }
    
    // 减少阻塞线程计数
    private void decWaiters() {
        --waiters;
    }

    private void rethrowIfFailed() {
    	// 获取异常
        Throwable cause = cause();
        if (cause == null) {
            return;
        }
        // 依赖java平台实现
        PlatformDependent.throwException(cause);
    }

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
    	// 已经完成,直接返回
        if (isDone()) {
            return true;
        }

        // 当阻塞时间小于0时,直接返回
        if (timeoutNanos <= 0) {
            return isDone();
        }

        // 线程已经被中断,抛出InterruptedException异常,并清除中断标志位
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }
        
        // 判断当前线程执行线程是否与Channel线程一致。
        // 如果一致的话，将会抛出BlockingOperationException异常
        // 我的理解是该ChannelPromise所持有的线程不允许调用自身wait方法
        // 即自己将自己给阻塞了,那么这个I/O操作将会一直阻塞下去
        checkDeadLock();

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        // 标识是否中断发生
        boolean interrupted = false;
        try {
            for (;;) {
                synchronized (this) {
                	// 已经完成,直接返回
                    if (isDone()) {
                        return true;
                    }
                    // 阻塞计数器递增
                    incWaiters();
                    try {
                    	// 阻塞指定时间后,自动唤醒线程
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                    	// 在阻塞期间,发生中断时
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                    	// 阻塞计数器递减
                        decWaiters();
                    }
                }
                // 已经完成,直接返回
                if (isDone()) {
                    return true;
                } else {
                	// 等待时间到达时,也会直接返回
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
        	// 如果中断发生过,即中断线程,处理中断响应
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
    	// 获取GenericProgressiveFutureListener数组对象
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
        	// 判断是否GenericProgressiveFutureListener[]类型对象
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
                // 创建任务封装进任务队列中
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                // 创建任务封装进任务队列中
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
                    	// 返回GenericProgressiveFutureListener对象
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            // 当progressiveSize > 1 时
            GenericFutureListener<?>[] array = dfl.listeners();
            // 创建GenericProgressiveFutureListener数组对象
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                	// 将GenericProgressiveFutureListener存储入copy数组中
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
        	// 直接就返回GenericProgressiveFutureListener对象
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        // 遍历GenericProgressiveFutureListener数组对象
    	for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            // 触发operationProgressed事件
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
        	// 运行operationProgressed方法
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
        	// 日志记录器
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
        }
    }

    // 当result为CancellationException时,返回true。判断当前Promise是否被撤销
    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    // 当result不为空且不为UNCANCELLABLE时,返回True
    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    // 静态内部类
    private static final class CauseHolder {
        final Throwable cause;
        // 持有异常信息
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
        	// 调用线程执行器的execute方法
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
