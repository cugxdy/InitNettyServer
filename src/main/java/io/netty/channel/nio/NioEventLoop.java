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
// NioEventLoop��ʵ��һ���ض����̰߳�, ������������������, �󶨵��̶߳������ٸı�.
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    // �����Ƿ����ö�Selector��selectedKeys�����Ż�,Ĭ��Ϊfalse
    private static final boolean DISABLE_KEYSET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    
    // Selector����ѯ����ֵ
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    // selectNow�ṩ�������¼�ѭ��������ѡ�����(selectStrategy)�С�
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
    	// �ڿ�ʼʹ��Selector.open()����֮ǰ,  NPE����
    	// �Ƚ�"sun.nio.ch.bugLevel"ϵͳ��������Ϊnon-null�ġ�
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
        // Ϊ�����¼�ѭ��ʱ���JDK NIO����epoll bug,  
        // �����ú�SELECTOR_AUTO_REBUILD_THRESHOLD,��selector����ѯ����ֵ
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
    // NioEventLoop��������ͨ������ͨ�� selector = provider.openSelector()��ȡһ�� selector����.
    private Selector selector;
    private Selector unwrappedSelector;
    private SelectedSelectionKeySet selectedKeys;

    // NioEventLoopGroup��������ͨ�� SelectorProvider.provider()��ȡһ�� SelectorProvider
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    // ԭ�����Boolean��ʶ���ڿ��ƾ���һ�������ŵ�Selector.select�Ƿ�Ӧ�ý�������ѡ�������
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private final SelectStrategy selectStrategy;

    // ���̷߳���� IO ������ռ��ʱ���
    private volatile int ioRatio = 50;
    
    private int cancelledKeys;
    
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, ThreadFactory threadFactory, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
    	
        super(parent, threadFactory, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        // ��ָ���쳣
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        
        // ����selecter�ṩ��
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        
        selector = selectorTuple.selector;
        // ����selecter��ѯ����
        unwrappedSelector = selectorTuple.unwrappedSelector;
        // ����ѡ�����
        selectStrategy = strategy;
    }

    
    // ��selector����ķ�װ
    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
        	// SelectorProvider.provider().openSelector()������Selector,�ް�װ����
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

        // ��ȡsun.nio.ch.SelectorImpl��Class����
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

        // �ж�maybeSelectorImplClass�Ƿ�ΪClass����,maybeSelectorImplClass����ΪunwrappedSelector����
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

        // ��selectedKeys��publicSelectedKeys����Ϊ������ʽ��������������
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                	// ��ȡselectedKeys����ֵ��publicSelectedKeys����ֵ
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    // ���÷��ʿ���Ȩ��
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    
                    // ���÷��ʿ���Ȩ��
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    
                    // ������Ӧ������ֵ
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
            // �����쳣ʱ��
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
    // ����provider�ֶ�
    public SelectorProvider selectorProvider() {
        return provider;
    }

    
    @Override // ����MpscQueue���Ͷ���
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
    	
    	return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
        // return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    // : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    @Override// ���ش�����������Ŀ
    public int pendingTasks() {
        // As we use a MpscQueue we need to ensure pendingTasks() is only executed from within the EventLoop as
        // otherwise we may see unexpected behavior (as size() is only allowed to be called by a single consumer).
        // See https://github.com/netty/netty/issues/5297
        if (inEventLoop()) {
            return super.pendingTasks();
        } else {
        	// �����첽����ͬ���ȴ��������
            return submit(pendingTasksCallable).syncUninterruptibly().getNow();
        }
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    // ע��SelectableChannel������selector�У�������NioTask<?>����
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

        // �ж�eventloop�Ƿ��Ѿ�shutdown״̬�С�
        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        try {
        	// ��SocketChannelע����selector�С�
            ch.register(selector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    // ��ȡioRatio�ֶ�
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    // ����ioRatio
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        // ���̷߳����IO������ռ��ʱ���
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */ 
    public void rebuildSelector() {
    	// �ж��Ƿ��������̷߳����rebulidSelector
    	// ����������̷߳���,Ϊ�˱�����̲߳�������Selector��������Դ,��Ҫ��rebulidSelector��װ��Task
    	// �ŵ�NioEventLoop����Ϣ������,��NioEventLoop�̸߳������,���������˶��̲߳����������µ��̰߳�ȫ����
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

    // ����Epoll ����ѵbug,��oldSelector�ϵ�SocketChannelǨ����newSelector�ϵ�SocketChannel��
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
        	// �����µ�Selector
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        // ��ԭSelector��ע���SocketChannelע�ᵽ�µ�Selector��,�����ϵ�Selector�رա�
        for (SelectionKey key: oldSelector.keys()) {
        	// aΪAbstractNioChannel���͵�ʵ��
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                // ��ȡSocketChannel�ϵ��������λ
                int interestOps = key.interestOps();
                // �ر�SelectionKey
                key.cancel();
                // ��SocketChannelע�ᵽ�µ�Slector��
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                	// ����selectionKey�ֶ�
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

        // ����selector��unwrappedSelector�ֶ�
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
        	// �رվɵ�Selector
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        // ���Info��Ϣ,Ǩ���׽��ֳɹ�
        logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
    }


    @Override  // �߳����й���
    protected void run() {
        for (;;) {
            try {
            	// �߳����л���,��ѭ��
                // ���������hasTasks() = trueʱ,���ȴ�����������е�����
            	switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:
                        continue;
                    case SelectStrategy.SELECT:
                    	// ��wakenUp����Ϊfalse
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
                // I/O����
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                    	// ȷ����������������
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                    	// ȷ���ܹ�ִ������
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // Task��ִ��ʱ����ݱ���I/O������ִ��ʱ����������
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
            	// �ж�ϵͳ�Ƿ��������ͣ��״̬
                if (isShuttingDown()) {
                	// ����closeAll����,�ͷ���Դ,����NioEventLoop�߳��˳�ѭ������������.
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
        	// �߳�˯��1s
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }
    
	// ��������������׽���
    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized(); // netty�����Ż�
        } else {
            processSelectedKeysPlain(selector.selectedKeys()); // nettyδ�����Ż�
        }
    }

    @Override // �ر�selector��ѯ����
    protected void cleanup() {
        try {
        	// selector��ѯ���йر�
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        // cancelledKeys������
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
        	// ���ü�����
            cancelledKeys = 0;
            // ��needsToSelectAgain = true,���д���I/Oʱ�ٴν���selectNow������
            needsToSelectAgain = true;
        }
    }

    @Override// ��ȡ��������е�Ԫ��,���ٴν���selectNow������
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    // δ�����Ż�ʱ,ʹ��Set<SelectionKey>���е���
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
    	// ��SelectionKey���б������ж�,���Ϊ���򷵻ء�
        if (selectedKeys.isEmpty()) {
            return;
        }

        // ��ȡselectedKeys�ĵ�����
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
        	// ��ȡSelectionKey
            final SelectionKey k = i.next();
            // SocketChannel�ĸ�������
            final Object a = k.attachment();
            // �ӵ�������ɾ��,��ֹ�´α��ظ�ѡ��ʹ���
            i.remove();

            // ��a���������ж�
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // �Ե�������һ��Ԫ���ж�
            if (!i.hasNext()) {
                break;
            }

            // �Ƿ���Ҫ��һ����ѯ,�����Ǵ���socketChannel�����������������
            if (needsToSelectAgain) {
                selectAgain();
                // ��ȡ����selectedKeys���󼯺�
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                	// ��ȡ������,�Ա���ConcurrentModificationException�쳣
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    // netty��selectKey�����Ż�,Set -> Array;
    private void processSelectedKeysOptimized() {
    	// ����selectedKeys����
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            // ΪAbstractNioChannelʵ��
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
            	// ��ΪNioServerSocketChannelʱ��ִ��processSelectedKey����
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
            	// ��ΪNioTask����ʱ
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // �Ƿ���һ�ν�����ѯ
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
    	// ��NioServerSocketChannel����NioSocketChannel�л�ȡ���ڲ���Unsafe
        final NioUnsafe unsafe = ch.unsafe();
        
        // �жϵ�ǰѡ����Ƿ����
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
            // ��ǰeventLoop����thisʱ,ֱ�ӷ���
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            // �ر�SocketChannel����
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
        	// ��ȡ�������λ
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // �ڽ��ж�дI/O����ʱ���������������ɲ����������׳�NotYetConnectedException
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // ע��SelectionKey.OP_CONNECT�������λ��־λ
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
                // ������Ӳ���
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // ������,˵���а����Ϣ��δ����,��Ҫ��������flush�������з���
            	ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
            	// ����Ƕ��������Ӳ���λ
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
        	// ��selectKeyʧЧʱ,�رն�ӦSocketChannel����
            unsafe.close(unsafe.voidPromise());
        }
    }

    // ������I/O�¼���SelectedKey
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
            	// �ж��Ƿ���channelRead�йر�selectKey����
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    // �ر����е�socket�׽�������
    private void closeAll() {
        selectAgain();
        // ��ȡ����ע��socket������
        Set<SelectionKey> keys = selector.keys();
        
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
        	// ��ȡ��������
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
            	// ����ΪAbstractNioChannel����ʱ
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        // �������е�Channel,��������Unsafe.close�����ر�������·,�ͷ��̳߳ء�ChannelPipline��ChannelHandler����Դ
        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    // ����ȡ��ע���¼�(pipline�д�������)
    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
        	// ����channelUnregistered����
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
    	// ��inEventLoopΪfalseʱ��
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
        	// selector.wakeup()��ֹ��Ϊselect(timeout)��select()���ö��������̡߳�
            selector.wakeup();
        }
    }

    // ����
    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    // ִ��selector.selectNow()����,�鿴�Ƿ���ھ����׽���,��������
    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
        	// �Ƿ��Ѵ��������е�selector
            if (wakenUp.get()) {
            	// selector.wakeup()��ֹ��Ϊselect(timeout)��select()���ö��������̡߳�
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
        	
        	// ����selector��ѯ����
            int selectCnt = 0;
            // ȡ��ǰϵͳ������ʱ��
            long currentTimeNanos = System.nanoTime();
            // ����delayNanos()����������NioEventLoop��������ﶨʱ����Ĵ���ʱ��
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);
            for (;;) {
            	// ������һ����Ҫ�����Ķ�ʱ�����ʣ�೬ʱʱ��,����ת���ɺ���,Ϊ��ʱʱ������0.5����ĵ���ֵ
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                
                // ��ʣ��ĳ�ʱʱ������ж�,�����Ҫ����ִ�л����Ѿ���ʱ
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                    	// ����selector.selectNow()������ѯ����������������
                        selector.selectNow();
                        // ��selectCnt����Ϊ1
                        selectCnt = 1;
                    }
                    // �˳�ѭ��
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

                // ����ʱ����ʣ��ĳ�ʱʱ����Ϊ��������select����,ÿ���һ��select����,�Լ�����selectCnt��һ
                // �߳������ڴ�
                int selectedKeys = selector.select(timeoutMillis);
                selectCnt ++;

                // Channel:����״̬,selectedKeys��=0,˵���ж�д�¼�����
                // oldWakenUp = true
                // ϵͳ���û�������wakeup����,���ѵ�ǰ�Ķ�·������
                // ��Ϣ���������µ�������Ҫ����
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                
                // ��ǰ�̴߳����ж���
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

                // ȡ��ǰϵͳ������ʱ��
                long time = System.nanoTime();
                
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                    // �ж��Ƿ񴥷�JDK�Ŀ���ѯbug
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The selector returned prematurely many times in a row.
                    // Rebuild the selector to work around the problem.
                	// ����JDK NIO��epoll()��ѭ��bug
                    logger.warn(
                            "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                            selectCnt, selector);

                    // ����epoll()��ѭ��bug
                    rebuildSelector();
                    selector = this.selector;

                    // Select again to populate selectedKeys.
                    // �ٴ�ִ����ѯ����
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

    // �Ƿ��ٴ�ִ����ѯ����(ΪʲôҪִ��selectNow����)
    private void selectAgain() {
    	// needsToSelectAgain����Ϊfalse,��ʾֻ�ᱻ����һ��
        needsToSelectAgain = false; 
        try {
        	// selectNow��������,��������
            selector.selectNow();
        } catch (Throwable t) {
        	// �׳�����
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
