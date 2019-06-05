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
package io.netty.channel;

import io.netty.channel.Channel.Unsafe;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.WeakHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * The default {@link ChannelPipeline} implementation.  It is usually created
 * by a {@link Channel} implementation when the {@link Channel} is created.
 */
public class DefaultChannelPipeline implements ChannelPipeline {

    static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultChannelPipeline.class);

    // 获取链表头结点名称 = HeadContext#0
    private static final String HEAD_NAME = generateName0(HeadContext.class);
    
    // 获取链表尾结点名称 = TailContext#0
    private static final String TAIL_NAME = generateName0(TailContext.class);

    // 缓存名称与处理器之间的一对一关系
    private static final FastThreadLocal<Map<Class<?>, String>> nameCaches =
            new FastThreadLocal<Map<Class<?>, String>>() {
        @Override
        protected Map<Class<?>, String> initialValue() throws Exception {
            return new WeakHashMap<Class<?>, String>();
        }
    };

    // 原子性改变字段值
    private static final AtomicReferenceFieldUpdater<DefaultChannelPipeline, MessageSizeEstimator.Handle> ESTIMATOR =
            AtomicReferenceFieldUpdater.newUpdater(
                    DefaultChannelPipeline.class, MessageSizeEstimator.Handle.class, "estimatorHandle");
    
    // 头结点AbstractChannelHandlerContext
    final AbstractChannelHandlerContext head;
    
    // 尾节点AbstractChannelHandlerContext
    final AbstractChannelHandlerContext tail;

    // 
    private final Channel channel;
    
    private Map<EventExecutorGroup, EventExecutor> childExecutors;
    
    private volatile MessageSizeEstimator.Handle estimatorHandle;
    
    // 是否为首次注册
    private boolean firstRegistration = true;

    /**
     * This is the head of a linked list that is processed by {@link #callHandlerAddedForAllHandlers()} and so process
     * all the pending {@link #callHandlerAdded0(AbstractChannelHandlerContext)}.
     *
     * We only keep the head because it is expected that the list is used infrequently and its size is small.
     * Thus full iterations to do insertions is assumed to be a good compromised to saving memory and tail management
     * complexity.
     */
    // 待处理处理器回调函数(单向链表)
    private PendingHandlerCallback pendingHandlerCallbackHead;

    /**
     * Set to {@code true} once the {@link AbstractChannel} is registered.Once set to {@code true} the value will never
     * change.
     */
    // 当AbstractChannel注册的时候被设置为true,设置之后以后就不会被改变.
    private boolean registered;

    // DefaultChannelPipeline的构造方法，是一个双向链表，并将 NioServerSocketChannel 设置为自己的属性
    protected DefaultChannelPipeline(Channel channel) {
        this.channel = ObjectUtil.checkNotNull(channel, "channel");

        // 创建双向链表的尾节点
        tail = new TailContext(this);
        
        // 创建双向链表的头节点
        head = new HeadContext(this);

        // 创建链表之间的指向关系
        head.next = tail;
        tail.prev = head;
    }

    final MessageSizeEstimator.Handle estimatorHandle() {
        MessageSizeEstimator.Handle handle = estimatorHandle;
        if (handle == null) {
        	// 获取MessageSizeEstimator处理器
            handle = channel.config().getMessageSizeEstimator().newHandle();
            // 原子性的改变estimatorHandle字段值
            if (!ESTIMATOR.compareAndSet(this, null, handle)) {
                handle = estimatorHandle;
            }
        }
        return handle;
    }

    //创建一个context，this是DefaultChannelPipeline，group为null，
    private AbstractChannelHandlerContext newContext(EventExecutorGroup group, String name, ChannelHandler handler) {
        // 创建DefaultChannelHandlerContext对象
    	return new DefaultChannelHandlerContext(this, childExecutor(group), name, handler);
    }

    
    // 获取EventExecutor线程执行器
    private EventExecutor childExecutor(EventExecutorGroup group) {
    	// 返回null
        if (group == null) {
            return null;
        }
        // 获取SINGLE_EVENTEXECUTOR_PER_GROUP属性
        Boolean pinEventExecutor = channel.config().getOption(ChannelOption.SINGLE_EVENTEXECUTOR_PER_GROUP);
        if (pinEventExecutor != null && !pinEventExecutor) {
        	// 获取线程执行器
            return group.next();
        }
        // 获取childExecutors
        Map<EventExecutorGroup, EventExecutor> childExecutors = this.childExecutors;
        if (childExecutors == null) {
            // Use size of 4 as most people only use one extra EventExecutor.
            childExecutors = this.childExecutors = new IdentityHashMap<EventExecutorGroup, EventExecutor>(4);
        }
        // Pin one of the child executors once and remember it so that the same child executor
        // is used to fire events for the same channel.
        EventExecutor childExecutor = childExecutors.get(group);
        if (childExecutor == null) {
            childExecutor = group.next();
            // 设置键值对
            childExecutors.put(group, childExecutor);
        }
        return childExecutor;
    }
    @Override// 获取SocketChannel字段值
    public final Channel channel() {
        return channel;
    }

    @Override
    public final ChannelPipeline addFirst(String name, ChannelHandler handler) {
        return addFirst(null, name, handler);
    }

    @Override
    public final ChannelPipeline addFirst(EventExecutorGroup group, String name, ChannelHandler handler) {
        // 创建AbstractChannelHandlerContext对象
    	final AbstractChannelHandlerContext newCtx;
        synchronized (this) {
            checkMultiplicity(handler);
            // 获取唯一的String名称
            name = filterName(name, handler);

            // 创建AbstractChannelHandlerContext对象
            newCtx = newContext(group, name, handler);

            // 将AbstractChannelHandlerContext对象添加至head后一个节点
            addFirst0(newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            // 还未注册时
            if (!registered) {
            	// 设置状态为ADD_PENDING
                newCtx.setAddPending();
                // 将AbstractChannelHandlerContext对象添加至pendingHandlerCallbackHead链表中
                callHandlerCallbackLater(newCtx, true);
                return this;
            }

            EventExecutor executor = newCtx.executor();
            // 当前执行线程与Thread字段不相等时
            if (!executor.inEventLoop()) {
                newCtx.setAddPending();
                // 封装成任务添加至任务队列中
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        // 设置handlerState = ADD_COMPLETE状态并调用handler对象的handlerAdded方法
        callHandlerAdded0(newCtx);
        return this;
    }

    // 将node节点添加至head后一个节点
    private void addFirst0(AbstractChannelHandlerContext newCtx) {
    	// 指针操作
        AbstractChannelHandlerContext nextCtx = head.next;
        newCtx.prev = head;
        newCtx.next = nextCtx;
        head.next = newCtx;
        nextCtx.prev = newCtx;
    }

    @Override 
    public final ChannelPipeline addLast(String name, ChannelHandler handler) {
        return addLast(null, name, handler);
    }

    @Override
    public final ChannelPipeline addLast(EventExecutorGroup group, String name, ChannelHandler handler) {
        // 创建AbstractChannelHandlerContext
    	final AbstractChannelHandlerContext newCtx;
        synchronized (this) {
        	//判断是否已经添加过handler。
            checkMultiplicity(handler);

            // 创建一个 DefaultChannelHandlerContext对象。
            newCtx = newContext(group, filterName(name, handler), handler);

            // 然后将 Context添加到链表中。也就是追加到 tail节点的前面。
            addLast0(newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            if (!registered) {
            	// 设置状态为ADD_PENDING
                newCtx.setAddPending();
                // 将AbstractChannelHandlerContext对象添加至pendingHandlerCallbackHead链表中
                callHandlerCallbackLater(newCtx, true);// 建议一个线程任务稍后执行。
                return this;
            }

            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                newCtx.setAddPending();
                // 封装成任务添加至任务队列中
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        
        // 设置handlerState = ADD_COMPLETE状态并调用handler对象的handlerAdded方法
        callHandlerAdded0(newCtx);
        return this;
    }

    // 添加一个context到pipline操作(pipline默认只有tail和head2个节点)，其实就是双向 链表的添加节点的操作。
    // 添加顺序为head------------newCtx - tail;
    private void addLast0(AbstractChannelHandlerContext newCtx) {
        AbstractChannelHandlerContext prev = tail.prev;
        newCtx.prev = prev;
        newCtx.next = tail;
        prev.next = newCtx;
        tail.prev = newCtx;
    }

    @Override // 在指定AbstractChannelHandlerContext的 之前添加处理器
    public final ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler) {
        return addBefore(null, baseName, name, handler);
    }

    @Override
    public final ChannelPipeline addBefore(
            EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        final AbstractChannelHandlerContext ctx;
        synchronized (this) {
        	// 判断是否已经添加过handler。
            checkMultiplicity(handler);
            
            // 获取处理器名称
            name = filterName(name, handler);
            
            // 获取基准AbstractChannelHandlerContext对象
            ctx = getContextOrDie(baseName);

            // 创建一个 DefaultChannelHandlerContext对象。
            newCtx = newContext(group, name, handler);

            // 将newCtx添加至ctx前面
            addBefore0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            if (!registered) {
            	// 设置状态为ADD_PENDING
                newCtx.setAddPending();
                // 将AbstractChannelHandlerContext对象添加至pendingHandlerCallbackHead链表中
                callHandlerCallbackLater(newCtx, true);
                return this;
            }

            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                newCtx.setAddPending();
                // 封装成任务添加至任务队列中
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        
        // 设置handlerState = ADD_COMPLETE状态并调用handler对象的handlerAdded方法
        callHandlerAdded0(newCtx);
        return this;
    }

    // 将newCtx添加至ctx前面
    private static void addBefore0(AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        newCtx.prev = ctx.prev;
        newCtx.next = ctx;
        ctx.prev.next = newCtx;
        ctx.prev = newCtx;
    }

    //如果name为空，生成一个名字
    private String filterName(String name, ChannelHandler handler) {
        if (name == null) { 
            return generateName(handler);
        }
        //判断名字是否重复
        checkDuplicateName(name);
        return name;
    }

    @Override// 在baseName之后添加name
    public final ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler) {
        return addAfter(null, baseName, name, handler);
    }

    @Override
    public final ChannelPipeline addAfter(
            EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        final AbstractChannelHandlerContext ctx;

        synchronized (this) {
        	// 判断是否已经添加过handler。
            checkMultiplicity(handler);
            
            // 获取处理器名称
            name = filterName(name, handler);
            
            // 获取基准AbstractChannelHandlerContext对象
            ctx = getContextOrDie(baseName);

            // 创建一个 DefaultChannelHandlerContext对象。
            newCtx = newContext(group, name, handler);

            // 将newCtx添加至ctx中
            addAfter0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we remove the context from the pipeline and add a task that will call
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            if (!registered) {
            	// 设置状态为ADD_PENDING
                newCtx.setAddPending();
                // 将AbstractChannelHandlerContext对象添加至pendingHandlerCallbackHead链表中
                callHandlerCallbackLater(newCtx, true);
                return this;
            }
            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
            	// 设置状态为ADD_PENDING
                newCtx.setAddPending();
                // 封装成任务添加至任务队列中
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        // 设置handlerState = ADD_COMPLETE状态并调用handler对象的handlerAdded方法
        callHandlerAdded0(newCtx);
        return this;
    }

    // 将newCtx添加至ctx中
    private static void addAfter0(AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        newCtx.prev = ctx;
        newCtx.next = ctx.next;
        ctx.next.prev = newCtx;
        ctx.next = newCtx;
    }

    @Override// 将handlers从头开始添加至链表中
    public final ChannelPipeline addFirst(ChannelHandler... handlers) {
        return addFirst(null, handlers);
    }

    @Override// 将handlers从头开始添加至链表中
    public final ChannelPipeline addFirst(EventExecutorGroup executor, ChannelHandler... handlers) {
        // 空指针异常
    	if (handlers == null) {
            throw new NullPointerException("handlers");
        }
    	// 输入参数handlers为空
        if (handlers.length == 0 || handlers[0] == null) {
            return this;
        }

        int size;
        for (size = 1; size < handlers.length; size ++) {
        	// 构造handlers数组
            if (handlers[size] == null) {
                break;
            }
        }

        // 遍历ChannelHandler数组
        for (int i = size - 1; i >= 0; i --) {
            ChannelHandler h = handlers[i];
            // 将ChannelHandler添加至链表中
            addFirst(executor, null, h);
        }

        return this;
    }

    @Override
    public final ChannelPipeline addLast(ChannelHandler... handlers) {
        return addLast(null, handlers);
    }

    @Override
    public final ChannelPipeline addLast(EventExecutorGroup executor, ChannelHandler... handlers) {
        // 当handlers为空时,抛出异常
    	if (handlers == null) {
            throw new NullPointerException("handlers");
        }

    	// 遍历ChannelHandler数组
        for (ChannelHandler h: handlers) {
            if (h == null) {
                break;
            }
            // 将ChannelHandler添加至链表中
            addLast(executor, null, h);
        }

        return this;
    }

    private String generateName(ChannelHandler handler) {
        Map<Class<?>, String> cache = nameCaches.get();
        
        // 获取handler的Class类型
        Class<?> handlerType = handler.getClass();
        // 从nameCaches中获取handlerType对应的name值
        String name = cache.get(handlerType);
        if (name == null) {
        	// 当name为空时,name = className + "#0"
            name = generateName0(handlerType);
            cache.put(handlerType, name);
        }

        // It's not very likely for a user to put more than one handler of the same type, but make sure to avoid
        // any name conflicts.  Note that we don't cache the names generated here.
        if (context0(name) != null) {
        	// className + "#0"  == 去除最后的0字符
            String baseName = name.substring(0, name.length() - 1); // Strip the trailing '0'.
            for (int i = 1;; i ++) {
            	// newName = className + "#1"
                String newName = baseName + i;
                // 当newName不重合时,退出循环
                if (context0(newName) == null) {
                    name = newName;
                    break;
                }
            }
        }
        return name;
    }

    private static String generateName0(Class<?> handlerType) {
    	// name = className + "#0"
        return StringUtil.simpleClassName(handlerType) + "#0";
    }

    @Override
    public final ChannelPipeline remove(ChannelHandler handler) {
    	// 删除指定的AbstractChannelHandlerContext对象
        remove(getContextOrDie(handler));
        return this;
    }

    @Override
    public final ChannelHandler remove(String name) {
        return remove(getContextOrDie(name)).handler();
    }

    @SuppressWarnings("unchecked")
    @Override
    public final <T extends ChannelHandler> T remove(Class<T> handlerType) {
        return (T) remove(getContextOrDie(handlerType)).handler();
    }

    private AbstractChannelHandlerContext remove(final AbstractChannelHandlerContext ctx) {
        assert ctx != head && ctx != tail;

        synchronized (this) {
        	// 从链表中删除AbstractChannelHandlerContext对象
            remove0(ctx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we remove the context from the pipeline and add a task that will call
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            // 当为注册时
            if (!registered) {
            	// 添加PendingHandlerRemovedTask任务队列删除AbstractChannelHandlerContext对象
                callHandlerCallbackLater(ctx, false);
                return ctx;
            }

            EventExecutor executor = ctx.executor();
            if (!executor.inEventLoop()) {
            	// 封装成任务添加至任务队列中
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerRemoved0(ctx);
                    }
                });
                return ctx;
            }
        }
        // 将AbstractChannelHandlerContext对象设置为REMOVE_COMPLETE状态,并触发handlrRemove方法
        callHandlerRemoved0(ctx);
        return ctx;
    }

    // 从执行器链中删除AbstractChannelHandlerContext并该改变前后指向指针
    private static void remove0(AbstractChannelHandlerContext ctx) {
        AbstractChannelHandlerContext prev = ctx.prev;
        AbstractChannelHandlerContext next = ctx.next;
        prev.next = next;
        next.prev = prev;
    }

    @Override // 删除head后的第一个AbstractChannelHandlerContext对象,并返回其Handler对象
    public final ChannelHandler removeFirst() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(head.next).handler();
    }

    @Override// 删除tail前的第一个AbstractChannelHandlerContext对象,并返回其Handler对象
    public final ChannelHandler removeLast() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(tail.prev).handler();
    }

    @Override// 用newHandler替换oldHandler
    public final ChannelPipeline replace(ChannelHandler oldHandler, String newName, ChannelHandler newHandler) {
        replace(getContextOrDie(oldHandler), newName, newHandler);
        return this;
    }

    @Override
    public final ChannelHandler replace(String oldName, String newName, ChannelHandler newHandler) {
        return replace(getContextOrDie(oldName), newName, newHandler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public final <T extends ChannelHandler> T replace(
            Class<T> oldHandlerType, String newName, ChannelHandler newHandler) {
        return (T) replace(getContextOrDie(oldHandlerType), newName, newHandler);
    }

    private ChannelHandler replace(
            final AbstractChannelHandlerContext ctx, final String newName, ChannelHandler newHandler) {
        assert ctx != head && ctx != tail;

        final AbstractChannelHandlerContext newCtx;
        synchronized (this) {
        	// 检验是否重复添加handler
            checkMultiplicity(newHandler);
            
            // 检验两者名称是否相同
            boolean sameName = ctx.name().equals(newName);
            if (!sameName) {
            	// 检验名称,防止添加相同的名称
                checkDuplicateName(newName);
            }

            // 创建AbstractChannelHandlerContext对象
            newCtx = newContext(ctx.executor, newName, newHandler);

            replace0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we replace the context in the pipeline
            // and add a task that will call ChannelHandler.handlerAdded(...) and
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            if (!registered) {
            	// 触发handlerAdded方法
                callHandlerCallbackLater(newCtx, true);
                // 触发handlerRemoved方法
                callHandlerCallbackLater(ctx, false);
                // 返回ctx的处理器handler
                return ctx.handler();
            }
            EventExecutor executor = ctx.executor();
            if (!executor.inEventLoop()) {
            	// 封装成异步任务至任务队列中
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Invoke newHandler.handlerAdded() first (i.e. before oldHandler.handlerRemoved() is invoked)
                        // because callHandlerRemoved() will trigger channelRead() or flush() on newHandler and
                        // those event handlers must be called after handlerAdded().
                    	// 异步触发callHandlerAdded0与callHandlerRemoved0事件
                        callHandlerAdded0(newCtx);
                        callHandlerRemoved0(ctx);
                    }
                });
                // 返回ctx的处理器handler
                return ctx.handler();
            }
        }
        // Invoke newHandler.handlerAdded() first (i.e. before oldHandler.handlerRemoved() is invoked)
        // because callHandlerRemoved() will trigger channelRead() or flush() on newHandler and those
        // event handlers must be called after handlerAdded().
        // 触发callHandlerAdded0与callHandlerRemoved0事件
        callHandlerAdded0(newCtx);
        callHandlerRemoved0(ctx);
        // 返回ctx的处理器handler
        return ctx.handler();
    }

    // 使用newCtx代替oldCtx在链表中的位置
    private static void replace0(AbstractChannelHandlerContext oldCtx, AbstractChannelHandlerContext newCtx) {
        AbstractChannelHandlerContext prev = oldCtx.prev;
        AbstractChannelHandlerContext next = oldCtx.next;
        newCtx.prev = prev;
        newCtx.next = next;

        // Finish the replacement of oldCtx with newCtx in the linked list.
        // Note that this doesn't mean events will be sent to the new handler immediately
        // because we are currently at the event handler thread and no more than one handler methods can be invoked
        // at the same time (we ensured that in replace().)
        prev.next = newCtx;
        next.prev = newCtx;

        // update the reference to the replacement so forward of buffered content will work correctly
        oldCtx.prev = newCtx;
        oldCtx.next = newCtx;
    }

    // 检验是否重复添加handler
    private static void checkMultiplicity(ChannelHandler handler) {
        if (handler instanceof ChannelHandlerAdapter) {
            ChannelHandlerAdapter h = (ChannelHandlerAdapter) handler;
            if (!h.isSharable() && h.added) { //不是共享的，并且被添加过直接抛出异常
                throw new ChannelPipelineException(
                        h.getClass().getName() +
                        " is not a @Sharable handler, so can't be added or removed multiple times.");
            }
            h.added = true; //设置added 标志位为true
        }
    }

    // context被添加到pipline之后调用callHandlerAdded0
    private void callHandlerAdded0(final AbstractChannelHandlerContext ctx) {
        try {
            // We must call setAddComplete before calling handlerAdded. Otherwise if the handlerAdded method generates
            // any pipeline events ctx.handler() will miss them because the state will not allow it.
            ctx.setAddComplete();
            // 执行handler添加成功回调函数
            ctx.handler().handlerAdded(ctx);
        } catch (Throwable t) {
            boolean removed = false;
            try {
            	// 从执行器链中删除该AbstractChannelHandlerContext
                remove0(ctx);
                try {
                	// 异常时,调用相应的处理器handlerRemoved
                    ctx.handler().handlerRemoved(ctx);
                } finally {
                	// 设置移除成功状态
                    ctx.setRemoved();
                }
                removed = true;
            } catch (Throwable t2) {
            	// 记录日志中
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to remove a handler: " + ctx.name(), t2);
                }
            }

            // 添加失败但是成功删除
            if (removed) {
                fireExceptionCaught(new ChannelPipelineException(
                        ctx.handler().getClass().getName() +
                        ".handlerAdded() has thrown an exception; removed.", t));
            } else {
                fireExceptionCaught(new ChannelPipelineException(
                        ctx.handler().getClass().getName() +
                        ".handlerAdded() has thrown an exception; also failed to remove.", t));
            }
        }
    }

    private void callHandlerRemoved0(final AbstractChannelHandlerContext ctx) {
        // Notify the complete removal.
        try {
            try {
            	// 执行Removed操作
                ctx.handler().handlerRemoved(ctx);
            } finally {
            	// 将其设置为REMOVE_COMPLETE状态
                ctx.setRemoved();
            }
        } catch (Throwable t) {
        	// 处理异常
            fireExceptionCaught(new ChannelPipelineException(
                    ctx.handler().getClass().getName() + ".handlerRemoved() has thrown an exception.", t));
        }
    }

    final void invokeHandlerAddedIfNeeded() {
        assert channel.eventLoop().inEventLoop();
        // 首次注册成功时
        if (firstRegistration) {
        	// 设置状态
            firstRegistration = false;
            // We are now registered to the EventLoop. It's time to call the callbacks for the ChannelHandlers,
            // that were added before the registration was done.
            // 处理所有的pendingHandlerCallbackHead任务
            callHandlerAddedForAllHandlers();
        }
    }

    @Override
    public final ChannelHandler first() {
        ChannelHandlerContext first = firstContext();
        if (first == null) {
            return null;
        }
        return first.handler();
    }

    @Override
    public final ChannelHandlerContext firstContext() {
        AbstractChannelHandlerContext first = head.next;
        if (first == tail) {
            return null;
        }
        return head.next;
    }

    @Override
    public final ChannelHandler last() {
        AbstractChannelHandlerContext last = tail.prev;
        if (last == head) {
            return null;
        }
        return last.handler();
    }

    @Override
    public final ChannelHandlerContext lastContext() {
        AbstractChannelHandlerContext last = tail.prev;
        if (last == head) {
            return null;
        }
        return last;
    }

    @Override
    public final ChannelHandler get(String name) {
        ChannelHandlerContext ctx = context(name);
        if (ctx == null) {
            return null;
        } else {
            return ctx.handler();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public final <T extends ChannelHandler> T get(Class<T> handlerType) {
        ChannelHandlerContext ctx = context(handlerType);
        if (ctx == null) {
            return null;
        } else {
            return (T) ctx.handler();
        }
    }

    @Override
    public final ChannelHandlerContext context(String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }

        return context0(name);
    }

    @Override// 依据handler获取ChannelHandlerContext对象
    public final ChannelHandlerContext context(ChannelHandler handler) {
    	// 空指针异常
        if (handler == null) {
            throw new NullPointerException("handler");
        }

        AbstractChannelHandlerContext ctx = head.next;
        // 循环遍历处理器链表
        for (;;) {

            if (ctx == null) {
                return null;
            }

            // 当处理器相等时,直接返回
            if (ctx.handler() == handler) {
                return ctx;
            }

            // 获取下一个指针
            ctx = ctx.next;
        }
    }

    @Override
    public final ChannelHandlerContext context(Class<? extends ChannelHandler> handlerType) {
        if (handlerType == null) {
            throw new NullPointerException("handlerType");
        }

        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == null) {
                return null;
            }
            if (handlerType.isAssignableFrom(ctx.handler().getClass())) {
                return ctx;
            }
            ctx = ctx.next;
        }
    }

    @Override// 将处理器名称添加至链表中
    public final List<String> names() {
        List<String> list = new ArrayList<String>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == null) {
                return list;
            }
            // list的add方法
            list.add(ctx.name());
            ctx = ctx.next;
        }
    }

    @Override// 将name与handler建立一对一的关系
    public final Map<String, ChannelHandler> toMap() {
        Map<String, ChannelHandler> map = new LinkedHashMap<String, ChannelHandler>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == tail) {
                return map;
            }
            // put方法,将键值对放进Map中
            map.put(ctx.name(), ctx.handler());
            ctx = ctx.next;
        }
    }

    @Override// 获取迭代器
    public final Iterator<Map.Entry<String, ChannelHandler>> iterator() {
        return toMap().entrySet().iterator();
    }

    /**
     * Returns the {@link String} representation of this pipeline.
     */
    @Override // 返回String对象
    public final String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('{');
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == tail) {
                break;
            }

            buf.append('(')
               .append(ctx.name())
               .append(" = ")
               .append(ctx.handler().getClass().getName())
               .append(')');

            ctx = ctx.next;
            if (ctx == tail) {
                break;
            }

            buf.append(", ");
        }
        buf.append('}');
        return buf.toString();
    }

    @Override// SocketChannel注册事件触发
    public final ChannelPipeline fireChannelRegistered() {
        AbstractChannelHandlerContext.invokeChannelRegistered(head);
        return this;
    }

    @Override// SocketChannel注销事件触发
    public final ChannelPipeline fireChannelUnregistered() {
        AbstractChannelHandlerContext.invokeChannelUnregistered(head);
        return this;
    }

    /**
     * Removes all handlers from the pipeline one by one from tail (exclusive) to head (exclusive) to trigger
     * handlerRemoved().
     *
     * Note that we traverse up the pipeline ({@link #destroyUp(AbstractChannelHandlerContext, boolean)})
     * before traversing down ({@link #destroyDown(Thread, AbstractChannelHandlerContext, boolean)}) so that
     * the handlers are removed after all events are handled.
     *
     * See: https://github.com/netty/netty/issues/3156
     */
    // 销毁所有的handler对象
    private synchronized void destroy() {
        destroyUp(head.next, false);
    }

    private void destroyUp(AbstractChannelHandlerContext ctx, boolean inEventLoop) {
        final Thread currentThread = Thread.currentThread();
        // 获取尾节点AbstractChannelHandlerContext对象
        final AbstractChannelHandlerContext tail = this.tail;
        for (;;) {
        	// 当ctx为链表尾节点时
            if (ctx == tail) {
                destroyDown(currentThread, tail.prev, inEventLoop);
                break;
            }

            final EventExecutor executor = ctx.executor();
            if (!inEventLoop && !executor.inEventLoop(currentThread)) {
                final AbstractChannelHandlerContext finalCtx = ctx;
                // 封装成异步任务添加至任务队列中去
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        destroyUp(finalCtx, true);
                    }
                });
                break;
            }

            ctx = ctx.next;
            inEventLoop = false;
        }
    }

    private void destroyDown(Thread currentThread, AbstractChannelHandlerContext ctx, boolean inEventLoop) {
        // We have reached at tail; now traverse backwards.
    	// 获取链表头结点
        final AbstractChannelHandlerContext head = this.head;
        for (;;) {
            if (ctx == head) {
                break;
            }

            // 获取线程执行器
            final EventExecutor executor = ctx.executor();
            if (inEventLoop || executor.inEventLoop(currentThread)) {
                synchronized (this) {
                	// 删除当前AbstractChannelHandlerContext对象
                    remove0(ctx);
                }
                // 执行处理器HandlerRemoved()方法
                callHandlerRemoved0(ctx);
            } else {
                final AbstractChannelHandlerContext finalCtx = ctx;
                // 添加异步任务
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        destroyDown(Thread.currentThread(), finalCtx, true);
                    }
                });
                break;
            }

            ctx = ctx.prev;
            inEventLoop = false;
        }
    }

    @Override// 触发ChannelActive事件
    public final ChannelPipeline fireChannelActive() {
        AbstractChannelHandlerContext.invokeChannelActive(head);
        return this;
    }

    @Override// 触发ChannelInactive事件
    public final ChannelPipeline fireChannelInactive() {
        AbstractChannelHandlerContext.invokeChannelInactive(head);
        return this;
    }

    @Override// 触发ExceptionCaught事件
    public final ChannelPipeline fireExceptionCaught(Throwable cause) {
        AbstractChannelHandlerContext.invokeExceptionCaught(head, cause);
        return this;
    }

    @Override// 触发UserEventTriggered事件
    public final ChannelPipeline fireUserEventTriggered(Object event) {
        AbstractChannelHandlerContext.invokeUserEventTriggered(head, event);
        return this;
    }

    @Override// 触发ChannelRead事件
    public final ChannelPipeline fireChannelRead(Object msg) {
        AbstractChannelHandlerContext.invokeChannelRead(head, msg);
        return this;
    }

    @Override// 触发ChannelReadComplete事件
    public final ChannelPipeline fireChannelReadComplete() {
        AbstractChannelHandlerContext.invokeChannelReadComplete(head);
        return this;
    }

    @Override// 触发ChannelWritabilityChanged事件
    public final ChannelPipeline fireChannelWritabilityChanged() {
        AbstractChannelHandlerContext.invokeChannelWritabilityChanged(head);
        return this;
    }

    @Override// 触发bind事件
    public final ChannelFuture bind(SocketAddress localAddress) {
        return tail.bind(localAddress);
    }

    @Override// 触发connect事件
    public final ChannelFuture connect(SocketAddress remoteAddress) {
        return tail.connect(remoteAddress);
    }

    @Override// 触发connect事件
    public final ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return tail.connect(remoteAddress, localAddress);
    }

    @Override// 触发disconnect事件
    public final ChannelFuture disconnect() {
        return tail.disconnect();
    }

    @Override// 触发close事件
    public final ChannelFuture close() {
        return tail.close();
    }

    @Override// 触发deregister事件
    public final ChannelFuture deregister() {
        return tail.deregister();
    }

    @Override// 触发flush事件
    public final ChannelPipeline flush() {
        tail.flush();
        return this;
    }

    @Override
    public final ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return tail.bind(localAddress, promise);
    }

    @Override
    public final ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return tail.connect(remoteAddress, promise);
    }

    @Override
    public final ChannelFuture connect(
            SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return tail.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public final ChannelFuture disconnect(ChannelPromise promise) {
        return tail.disconnect(promise);
    }

    @Override
    public final ChannelFuture close(ChannelPromise promise) {
        return tail.close(promise);
    }

    @Override
    public final ChannelFuture deregister(final ChannelPromise promise) {
        return tail.deregister(promise);
    }

    @Override
    public final ChannelPipeline read() {
        tail.read();
        return this;
    }

    @Override
    public final ChannelFuture write(Object msg) {
        return tail.write(msg);
    }

    @Override
    public final ChannelFuture write(Object msg, ChannelPromise promise) {
        return tail.write(msg, promise);
    }

    @Override
    public final ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return tail.writeAndFlush(msg, promise);
    }

    @Override
    public final ChannelFuture writeAndFlush(Object msg) {
        return tail.writeAndFlush(msg);
    }

    private void checkDuplicateName(String name) {
    	// 防止重复添加相同的Handler
        if (context0(name) != null) {
            throw new IllegalArgumentException("Duplicate handler name: " + name);
        }
    }

    private AbstractChannelHandlerContext context0(String name) {
    	// 遍历执行器链
        AbstractChannelHandlerContext context = head.next;
        while (context != tail) {
        	// 查看链表中是否已经有相同的Handler
            if (context.name().equals(name)) {
                return context;
            }
            // 指向下一个链表节点
            context = context.next;
        }
        return null;
    }

    // 依据名称获取AbstractChannelHandlerContext对象
    private AbstractChannelHandlerContext getContextOrDie(String name) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(name);
        if (ctx == null) {
            throw new NoSuchElementException(name);
        } else {
            return ctx;
        }
    }

    // 获取handler对应的AbstractChannelHandlerContext对象
    private AbstractChannelHandlerContext getContextOrDie(ChannelHandler handler) {
    	// 依据handler获取AbstractChannelHandlerContext对象
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(handler);
        if (ctx == null) {
        	// 当前链表中不存在该处理器,抛出异常!
            throw new NoSuchElementException(handler.getClass().getName());
        } else {
            return ctx;
        }
    }

    // 依据处理器类型获取AbstractChannelHandlerContext对象
    private AbstractChannelHandlerContext getContextOrDie(Class<? extends ChannelHandler> handlerType) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(handlerType);
        if (ctx == null) {
            throw new NoSuchElementException(handlerType.getName());
        } else {
            return ctx;
        }
    }

    private void callHandlerAddedForAllHandlers() {
        final PendingHandlerCallback pendingHandlerCallbackHead;
        synchronized (this) {
            assert !registered;

            // This Channel itself was registered.
            registered = true;

            // 获取pendingHandlerCallbackHead
            pendingHandlerCallbackHead = this.pendingHandlerCallbackHead;
            // Null out so it can be GC'ed.
            this.pendingHandlerCallbackHead = null;
        }

        // This must happen outside of the synchronized(...) block as otherwise handlerAdded(...) may be called while
        // holding the lock and so produce a deadlock if handlerAdded(...) will try to add another handler from outside
        // the EventLoop.
        PendingHandlerCallback task = pendingHandlerCallbackHead;
        // 执行任务队列
        while (task != null) {
        	// 执行pendingHandlerCallbackHead任务
            task.execute();
            task = task.next;
        }
    }

    private void callHandlerCallbackLater(AbstractChannelHandlerContext ctx, boolean added) {
    	// 当前还未注册中
        assert !registered;

        PendingHandlerCallback task = added ? new PendingHandlerAddedTask(ctx) : new PendingHandlerRemovedTask(ctx);
        // 获取头结点指针
        PendingHandlerCallback pending = pendingHandlerCallbackHead;
        if (pending == null) {
            pendingHandlerCallbackHead = task;
        } else {
            // Find the tail of the linked-list.
        	// 将AbstractChannelHandlerContext对象添加至末尾
            while (pending.next != null) {
                pending = pending.next;
            }
            // 将task添加至链表末尾
            pending.next = task;
        }
    }

    /**
     * Called once a {@link Throwable} hit the end of the {@link ChannelPipeline} without been handled by the user
     * in {@link ChannelHandler#exceptionCaught(ChannelHandlerContext, Throwable)}.
     */
    protected void onUnhandledInboundException(Throwable cause) {
        try {
        	// 日志记录
            logger.warn(
                    "An exceptionCaught() event was fired, and it reached at the tail of the pipeline. " +
                            "It usually means the last handler in the pipeline did not handle the exception.",
                    cause);
        } finally {
        	// 引用计数递减
            ReferenceCountUtil.release(cause);
        }
    }

    /**
     * Called once a message hit the end of the {@link ChannelPipeline} without been handled by the user
     * in {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}. This method is responsible
     * to call {@link ReferenceCountUtil#release(Object)} on the given msg at some point.
     */
    protected void onUnhandledInboundMessage(Object msg) {
        try {
        	// 日志记录
            logger.debug(
                    "Discarded inbound message {} that reached at the tail of the pipeline. " +
                            "Please check your pipeline configuration.", msg);
        } finally {
        	// 引用计数递减
            ReferenceCountUtil.release(msg);
        }
    }

    // A special catch-all handler that handles both bytes and messages.
    // 入栈处理器
    final class TailContext extends AbstractChannelHandlerContext implements ChannelInboundHandler {

        TailContext(DefaultChannelPipeline pipeline) {
            super(pipeline, null, TAIL_NAME, true, false);
            // 设置添加完成
            setAddComplete();
        }

        @Override// 获取this
        public ChannelHandler handler() {
            return this;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            // This may not be a configuration error and so don't log anything.
            // The event may be superfluous for the current pipeline configuration.
        	// 引用计数递减
            ReferenceCountUtil.release(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            onUnhandledInboundException(cause);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            onUnhandledInboundMessage(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception { }
    }

    // 出栈处理器
    final class HeadContext extends AbstractChannelHandlerContext
            implements ChannelOutboundHandler, ChannelInboundHandler {

    	// 引用SocketChannel中的Unsafe类
        private final Unsafe unsafe;

        HeadContext(DefaultChannelPipeline pipeline) {
            super(pipeline, null, HEAD_NAME, false, true);
            unsafe = pipeline.channel().unsafe();
            setAddComplete();
        }

        @Override // 返回本身
        public ChannelHandler handler() {
            return this;
        }

        @Override// 基本上什么都不做
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            // NOOP
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            // NOOP
        }

        @Override
        public void bind(
                ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise)
                throws Exception {
        	// 调用unsafe.bind()方法
            unsafe.bind(localAddress, promise);
        }

        @Override
        public void connect(
                ChannelHandlerContext ctx,
                SocketAddress remoteAddress, SocketAddress localAddress,
                ChannelPromise promise) throws Exception {
        	// unsafe发起TCP连接请求
            unsafe.connect(remoteAddress, localAddress, promise);
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        	// unsafe取消连接
        	unsafe.disconnect(promise);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        	// unsafe关闭连接
            unsafe.close(promise);
        }

        @Override
        public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        	// unsafe注销
            unsafe.deregister(promise);
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
        	// unsafe设置读操作位
            unsafe.beginRead();
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        	// unsafe写入数据
        	unsafe.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) throws Exception {
        	// unsafe发起flush()
            unsafe.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        	// 触发fireExceptionCaught事件
            ctx.fireExceptionCaught(cause);
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        	// 检查通道中是否有需要延迟执行的任务，如果有，就执行，然后调用 Context的 fireChannelRegistered方法，
        	// 而不是 pipeline的 fireChannelRegistered方法
            invokeHandlerAddedIfNeeded();
            ctx.fireChannelRegistered();
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelUnregistered();

            // Remove all handlers sequentially if channel is closed and unregistered.
            // 如果SocketChannel删除所有的处理器
            if (!channel.isOpen()) {
                destroy();
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelActive();

            readIfIsAutoRead();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelInactive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ctx.fireChannelRead(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelReadComplete();

            // 
            readIfIsAutoRead();
        }

        private void readIfIsAutoRead() {
            if (channel.config().isAutoRead()) {
                channel.read();
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelWritabilityChanged();
        }
    }

    private abstract static class PendingHandlerCallback implements Runnable {
        final AbstractChannelHandlerContext ctx;
        
        // 后继指针指向下一个PendingHandlerCallback
        PendingHandlerCallback next;

        PendingHandlerCallback(AbstractChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        // 子类实现
        abstract void execute();
    }

    // 处理器添加任务
    private final class PendingHandlerAddedTask extends PendingHandlerCallback {

    	// 设置AbstractChannelHandlerContext属性
        PendingHandlerAddedTask(AbstractChannelHandlerContext ctx) {
            super(ctx); // 指向PendingHandlerCallback类
        }

        @Override
        public void run() {
            callHandlerAdded0(ctx);
        }

        @Override
        void execute() {
        	// 获取线程执行器
            EventExecutor executor = ctx.executor();
            if (executor.inEventLoop()) {
            	// 调用callHandlerAdded0方法
                callHandlerAdded0(ctx);
            } else {
                try {
                    executor.execute(this);
                } catch (RejectedExecutionException e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(
                                "Can't invoke handlerAdded() as the EventExecutor {} rejected it, removing handler {}.",
                                executor, ctx.name(), e);
                    }
                    remove0(ctx);
                    ctx.setRemoved();
                }
            }
        }
    }

    private final class PendingHandlerRemovedTask extends PendingHandlerCallback {

        PendingHandlerRemovedTask(AbstractChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        public void run() {
        	// 从执行器链中删除AbstractChannelHandlerContext对象
            callHandlerRemoved0(ctx);
        }

        @Override
        void execute() {
            EventExecutor executor = ctx.executor();
            if (executor.inEventLoop()) {
            	// 从执行器链中删除AbstractChannelHandlerContext对象
                callHandlerRemoved0(ctx);
            } else {
                try {
                    executor.execute(this);
                } catch (RejectedExecutionException e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(
                                "Can't invoke handlerRemoved() as the EventExecutor {} rejected it," +
                                        " removing handler {}.", executor, ctx.name(), e);
                    }
                    // remove0(...) was call before so just call AbstractChannelHandlerContext.setRemoved().
                    // 设置状态为REMOVE_COMPLETE
                    ctx.setRemoved();
                }
            }
        }
    }
}
