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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.channel.nio.AbstractNioChannel.NioUnsafe;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
// NioEventLoop其实和一个特定的线程绑定, 并且在其生命周期内, 绑定的线程都不会再改变.
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    // 决定是否启用对Selector的selectedKeys进行优化,默认为false
    private static final boolean DISABLE_KEYSET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    
    // Selector空轮询的阈值
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    // selectNow提供器，在事件循环里用于选择策略(selectStrategy)中。
    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };
    
    private final Callable<Integer> pendingTasksCallable = new Callable<Integer>() {
        @Override
        public Integer call() throws Exception {
            return NioEventLoop.super.pendingTasks();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
    	// 在开始使用Selector.open()方法之前,  NPE问题
    	// 先将"sun.nio.ch.bugLevel"系统属性设置为non-null的。
        final String key = "sun.nio.ch.bugLevel";
        final String buglevel = SystemPropertyUtil.get(key);
        if (buglevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }
        
        // NioEventLoop event = new NioEventLoop(null, null, null, null, null);
        
        System.out.println("value = " + key);
        // 为了在事件循环时解决JDK NIO类库的epoll bug,  
        // 先设置好SELECTOR_AUTO_REBUILD_THRESHOLD,即selector空轮询的阈值
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }
        
        Field[] fields = SelectStrategy.class.getDeclaredFields();
        for(Field field : fields) {
        	System.out.println("field = " + Modifier.toString(field.getModifiers()));
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        System.out.println("logger = " + logger.isDebugEnabled());
        System.out.println("LoggerClassName = "  + logger.getClass().getName());
        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEYSET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    // NioEventLoop构造器中通过调用通过 selector = provider.openSelector()获取一个 selector对象.
    private Selector selector;
    private Selector unwrappedSelector;
    private SelectedSelectionKeySet selectedKeys;

    // NioEventLoopGroup构造器中通过 SelectorProvider.provider()获取一个 SelectorProvider
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    // 原子类的Boolean标识用于控制决定一个阻塞着的Selector.select是否应该结束它的选择操作。
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private final SelectStrategy selectStrategy;

    // 此线程分配给 IO 操作所占的时间比
    private volatile int ioRatio = 50;
    
    private int cancelledKeys;
    
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, ThreadFactory threadFactory, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
    	
        super(parent, threadFactory, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        // 空指针异常
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        
        // 设置selecter提供者
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        
        selector = selectorTuple.selector;
        // 返回selecter轮询队列
        unwrappedSelector = selectorTuple.unwrappedSelector;
        // 设置选择策略
        selectStrategy = strategy;
    }

    
    // 对selector对象的封装
    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
        	// SelectorProvider.provider().openSelector()开启的Selector,无包装的类
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEYSET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        System.out.println("SelectClass = " + unwrappedSelector.getClass().getName());
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        // 获取sun.nio.ch.SelectorImpl的Class对象
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                	
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 判断maybeSelectorImplClass是否为Class对象,maybeSelectorImplClass必须为unwrappedSelector父类
        if (!(maybeSelectorImplClass instanceof Class) ||
                // ensure the current selector implementation is what we can instrument.
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;

        // 将selectedKeys与publicSelectedKeys设置为数组形式。利于索引计算
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                	// 获取selectedKeys属性值与publicSelectedKeys属性值
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    // 设置访问控制权限
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    
                    // 设置访问控制权限
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    
                    // 设置相应的属性值
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            // 出现异常时。
            return new SelectorTuple(unwrappedSelector);
        }
        
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    // 返回provider字段
    public SelectorProvider selectorProvider() {
        return provider;
    }

    
    @Override // 返回MpscQueue类型队列
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
    	
    	return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
        // return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    // : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    @Override// 返回待处理任务数目
    public int pendingTasks() {
        // As we use a MpscQueue we need to ensure pendingTasks() is only executed from within the EventLoop as
        // otherwise we may see unexpected behavior (as size() is only allowed to be called by a single consumer).
        // See https://github.com/netty/netty/issues/5297
        if (inEventLoop()) {
            return super.pendingTasks();
        } else {
        	// 处理异步任务并同步等待任务完成
            return submit(pendingTasksCallable).syncUninterruptibly().getNow();
        }
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    // 注册SelectableChannel对象至selector中，并配置NioTask<?>对象
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        // 判断eventloop是否已经shutdown状态中。
        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        try {
        	// 将SocketChannel注册至selector中。
            ch.register(selector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    // 获取ioRatio字段
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    // 设置ioRatio
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        // 此线程分配给IO操作所占的时间比
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */ 
    public void rebuildSelector() {
    	// 判断是否是其他线程发起的rebulidSelector
    	// 如果由其他线程发起,为了避免多线程并发操作Selector和其他资源,需要将rebulidSelector封装成Task
    	// 放到NioEventLoop的消息队列中,由NioEventLoop线程负责调用,这样避免了多线程并发操作导致的线程安全问题
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    // 处理Epoll 空轮训bug,将oldSelector上的SocketChannel迁移至newSelector上的SocketChannel上
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
        	// 创建新的Selector
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        // 将原Selector上注册的SocketChannel注册到新的Selector上,并将老的Selector关闭。
        for (SelectionKey key: oldSelector.keys()) {
        	// a为AbstractNioChannel类型的实例
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                // 获取SocketChannel上的网络操作位
                int interestOps = key.interestOps();
                // 关闭SelectionKey
                key.cancel();
                // 将SocketChannel注册到新的Slector上
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                	// 更新selectionKey字段
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        // 更新selector与unwrappedSelector字段
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
        	// 关闭旧的Selector
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        // 输出Info信息,迁移套接字成功
        logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
    }


    @Override  // 线程运行过程
    protected void run() {
        for (;;) {
            try {
            	// 线程运行环境,死循环
                // 在这里如果hasTasks() = true时,会先处理任务队列中的任务。
            	switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:
                        continue;
                    case SelectStrategy.SELECT:
                    	// 将wakenUp设置为false
                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                // I/O比率
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                    	// 确保运行完所有任务
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                    	// 确保能够执行任务
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // Task的执行时间根据本次I/O操作的执行时间计算得来。
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
            	// 判断系统是否进入优雅停机状态
                if (isShuttingDown()) {
                	// 调用closeAll方法,释放资源,并让NioEventLoop线程退出循环，结束运行.
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
        	// 线程睡眠1s
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }
    
	// 处理就绪的网络套接字
    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized(); // netty进行优化
        } else {
            processSelectedKeysPlain(selector.selectedKeys()); // netty未进行优化
        }
    }

    @Override // 关闭selector轮询队列
    protected void cleanup() {
        try {
        	// selector轮询队列关闭
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        // cancelledKeys计数器
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
        	// 重置计数器
            cancelledKeys = 0;
            // 将needsToSelectAgain = true,进行处理I/O时再次进行selectNow操作。
            needsToSelectAgain = true;
        }
    }

    @Override// 获取任务队列中的元素,并再次进行selectNow操作。
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    // 未进行优化时,使用Set<SelectionKey>进行迭代
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
    	// 对SelectionKey进行保护性判断,如果为空则返回。
        if (selectedKeys.isEmpty()) {
            return;
        }

        // 获取selectedKeys的迭代器
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
        	// 获取SelectionKey
            final SelectionKey k = i.next();
            // SocketChannel的附件对象
            final Object a = k.attachment();
            // 从迭代器中删除,防止下次被重复选择和处理
            i.remove();

            // 对a进行类型判断
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 对迭代器下一个元素判断
            if (!i.hasNext()) {
                break;
            }

            // 是否需要再一次轮询,可能是存在socketChannel被撤销的情况发生。
            if (needsToSelectAgain) {
                selectAgain();
                // 获取就绪selectedKeys对象集合
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                	// 获取迭代器,以避免ConcurrentModificationException异常
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    // netty对selectKey进行优化,Set -> Array;
    private void processSelectedKeysOptimized() {
    	// 遍历selectedKeys集合
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            // 为AbstractNioChannel实例
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
            	// 当为NioServerSocketChannel时，执行processSelectedKey方法
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
            	// 当为NioTask类型时
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 是否再一次进行轮询
            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
    	// 从NioServerSocketChannel或者NioSocketChannel中获取其内部类Unsafe
        final NioUnsafe unsafe = ch.unsafe();
        
        // 判断当前选择键是否可用
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            // 当前eventLoop并非this时,直接返回
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            // 关闭SocketChannel对象
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
        	// 获取网络操作位
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // 在进行读写I/O操作时，必须进行连接完成操作。否则将抛出NotYetConnectedException
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // 注销SelectionKey.OP_CONNECT网络操作位标志位
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
                // 完成连接操作
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 读操作,说明有半包消息尚未发送,需要继续调用flush方法进行发送
            	ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
            	// 如果是读或者连接操作位
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
        	// 当selectKey失效时,关闭对应SocketChannel对象
            unsafe.close(unsafe.voidPromise());
        }
    }

    // 处理发生I/O事件的SelectedKey
    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
            	// 判断是否在channelRead中关闭selectKey对象
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    // 关闭所有的socket套接字描述
    private void closeAll() {
        selectAgain();
        // 获取所有注册socket描述符
        Set<SelectionKey> keys = selector.keys();
        
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
        	// 获取描述对象
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
            	// 当不为AbstractNioChannel类型时
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        // 遍历所有的Channel,调用它的Unsafe.close方法关闭所有链路,释放线程池、ChannelPipline和ChannelHandler等资源
        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    // 触发取消注册事件(pipline中触发调用)
    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
        	// 调用channelUnregistered方法
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
    	// 当inEventLoop为false时。
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
        	// selector.wakeup()终止因为select(timeout)和select()调用而阻塞的线程。
            selector.wakeup();
        }
    }

    // 返回
    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    // 执行selector.selectNow()方法,查看是否存在就绪套接字,立即返回
    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
        	// 是否唤醒处于阻塞中的selector
            if (wakenUp.get()) {
            	// selector.wakeup()终止因为select(timeout)和select()调用而阻塞的线程。
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
        	
        	// 计算selector轮询次数
            int selectCnt = 0;
            // 取当前系统的纳秒时间
            long currentTimeNanos = System.nanoTime();
            // 调用delayNanos()方法计算获得NioEventLoop中最近到达定时任务的触发时间
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);
            for (;;) {
            	// 计算下一个将要触发的定时任务的剩余超时时间,将它转换成毫秒,为超时时间增加0.5毫秒的调整值
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                
                // 对剩余的超时时间进行判断,如果需要立即执行或者已经超时
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                    	// 调用selector.selectNow()进行轮询操作。非阻塞操作
                        selector.selectNow();
                        // 将selectCnt设置为1
                        selectCnt = 1;
                    }
                    // 退出循环
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                // 将定时任务剩余的超时时间作为参数进行select操作,每完成一次select操作,对计数器selectCnt加一
                // 线程阻塞在此
                int selectedKeys = selector.select(timeoutMillis);
                selectCnt ++;

                // Channel:就绪状态,selectedKeys！=0,说明有读写事件发生
                // oldWakenUp = true
                // 系统或用户调用了wakeup操作,唤醒当前的多路复用器
                // 消息队列中有新的任务需要处理
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                
                // 当前线程处于中断中
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                // 取当前系统的纳秒时间
                long time = System.nanoTime();
                
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                    // 判断是否触发JDK的空轮询bug
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The selector returned prematurely many times in a row.
                    // Rebuild the selector to work around the problem.
                	// 触发JDK NIO的epoll()死循环bug
                    logger.warn(
                            "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                            selectCnt, selector);

                    // 处理epoll()死循环bug
                    rebuildSelector();
                    selector = this.selector;

                    // Select again to populate selectedKeys.
                    // 再次执行轮询操作
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    // 是否再次执行轮询操作(为什么要执行selectNow操作)
    private void selectAgain() {
    	// needsToSelectAgain设置为false,表示只会被调用一次
        needsToSelectAgain = false; 
        try {
        	// selectNow不会阻塞,立即返回
            selector.selectNow();
        } catch (Throwable t) {
        	// 抛出错误
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
