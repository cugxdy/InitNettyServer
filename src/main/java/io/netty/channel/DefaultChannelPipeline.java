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

    // ��ȡ����ͷ������� = HeadContext#0
    private static final String HEAD_NAME = generateName0(HeadContext.class);
    
    // ��ȡ����β������� = TailContext#0
    private static final String TAIL_NAME = generateName0(TailContext.class);

    // ���������봦����֮���һ��һ��ϵ
    private static final FastThreadLocal<Map<Class<?>, String>> nameCaches =
            new FastThreadLocal<Map<Class<?>, String>>() {
        @Override
        protected Map<Class<?>, String> initialValue() throws Exception {
            return new WeakHashMap<Class<?>, String>();
        }
    };

    // ԭ���Ըı��ֶ�ֵ
    private static final AtomicReferenceFieldUpdater<DefaultChannelPipeline, MessageSizeEstimator.Handle> ESTIMATOR =
            AtomicReferenceFieldUpdater.newUpdater(
                    DefaultChannelPipeline.class, MessageSizeEstimator.Handle.class, "estimatorHandle");
    
    // ͷ���AbstractChannelHandlerContext
    final AbstractChannelHandlerContext head;
    
    // β�ڵ�AbstractChannelHandlerContext
    final AbstractChannelHandlerContext tail;

    // 
    private final Channel channel;
    
    private Map<EventExecutorGroup, EventExecutor> childExecutors;
    
    private volatile MessageSizeEstimator.Handle estimatorHandle;
    
    // �Ƿ�Ϊ�״�ע��
    private boolean firstRegistration = true;

    /**
     * This is the head of a linked list that is processed by {@link #callHandlerAddedForAllHandlers()} and so process
     * all the pending {@link #callHandlerAdded0(AbstractChannelHandlerContext)}.
     *
     * We only keep the head because it is expected that the list is used infrequently and its size is small.
     * Thus full iterations to do insertions is assumed to be a good compromised to saving memory and tail management
     * complexity.
     */
    // �����������ص�����(��������)
    private PendingHandlerCallback pendingHandlerCallbackHead;

    /**
     * Set to {@code true} once the {@link AbstractChannel} is registered.Once set to {@code true} the value will never
     * change.
     */
    // ��AbstractChannelע���ʱ������Ϊtrue,����֮���Ժ�Ͳ��ᱻ�ı�.
    private boolean registered;

    // DefaultChannelPipeline�Ĺ��췽������һ��˫���������� NioServerSocketChannel ����Ϊ�Լ�������
    protected DefaultChannelPipeline(Channel channel) {
        this.channel = ObjectUtil.checkNotNull(channel, "channel");

        // ����˫�������β�ڵ�
        tail = new TailContext(this);
        
        // ����˫�������ͷ�ڵ�
        head = new HeadContext(this);

        // ��������֮���ָ���ϵ
        head.next = tail;
        tail.prev = head;
    }

    final MessageSizeEstimator.Handle estimatorHandle() {
        MessageSizeEstimator.Handle handle = estimatorHandle;
        if (handle == null) {
        	// ��ȡMessageSizeEstimator������
            handle = channel.config().getMessageSizeEstimator().newHandle();
            // ԭ���Եĸı�estimatorHandle�ֶ�ֵ
            if (!ESTIMATOR.compareAndSet(this, null, handle)) {
                handle = estimatorHandle;
            }
        }
        return handle;
    }

    //����һ��context��this��DefaultChannelPipeline��groupΪnull��
    private AbstractChannelHandlerContext newContext(EventExecutorGroup group, String name, ChannelHandler handler) {
        // ����DefaultChannelHandlerContext����
    	return new DefaultChannelHandlerContext(this, childExecutor(group), name, handler);
    }

    
    // ��ȡEventExecutor�߳�ִ����
    private EventExecutor childExecutor(EventExecutorGroup group) {
    	// ����null
        if (group == null) {
            return null;
        }
        // ��ȡSINGLE_EVENTEXECUTOR_PER_GROUP����
        Boolean pinEventExecutor = channel.config().getOption(ChannelOption.SINGLE_EVENTEXECUTOR_PER_GROUP);
        if (pinEventExecutor != null && !pinEventExecutor) {
        	// ��ȡ�߳�ִ����
            return group.next();
        }
        // ��ȡchildExecutors
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
            // ���ü�ֵ��
            childExecutors.put(group, childExecutor);
        }
        return childExecutor;
    }
    @Override// ��ȡSocketChannel�ֶ�ֵ
    public final Channel channel() {
        return channel;
    }

    @Override
    public final ChannelPipeline addFirst(String name, ChannelHandler handler) {
        return addFirst(null, name, handler);
    }

    @Override
    public final ChannelPipeline addFirst(EventExecutorGroup group, String name, ChannelHandler handler) {
        // ����AbstractChannelHandlerContext����
    	final AbstractChannelHandlerContext newCtx;
        synchronized (this) {
            checkMultiplicity(handler);
            // ��ȡΨһ��String����
            name = filterName(name, handler);

            // ����AbstractChannelHandlerContext����
            newCtx = newContext(group, name, handler);

            // ��AbstractChannelHandlerContext���������head��һ���ڵ�
            addFirst0(newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            // ��δע��ʱ
            if (!registered) {
            	// ����״̬ΪADD_PENDING
                newCtx.setAddPending();
                // ��AbstractChannelHandlerContext���������pendingHandlerCallbackHead������
                callHandlerCallbackLater(newCtx, true);
                return this;
            }

            EventExecutor executor = newCtx.executor();
            // ��ǰִ���߳���Thread�ֶβ����ʱ
            if (!executor.inEventLoop()) {
                newCtx.setAddPending();
                // ��װ��������������������
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        // ����handlerState = ADD_COMPLETE״̬������handler�����handlerAdded����
        callHandlerAdded0(newCtx);
        return this;
    }

    // ��node�ڵ������head��һ���ڵ�
    private void addFirst0(AbstractChannelHandlerContext newCtx) {
    	// ָ�����
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
        // ����AbstractChannelHandlerContext
    	final AbstractChannelHandlerContext newCtx;
        synchronized (this) {
        	//�ж��Ƿ��Ѿ���ӹ�handler��
            checkMultiplicity(handler);

            // ����һ�� DefaultChannelHandlerContext����
            newCtx = newContext(group, filterName(name, handler), handler);

            // Ȼ�� Context��ӵ������С�Ҳ����׷�ӵ� tail�ڵ��ǰ�档
            addLast0(newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            if (!registered) {
            	// ����״̬ΪADD_PENDING
                newCtx.setAddPending();
                // ��AbstractChannelHandlerContext���������pendingHandlerCallbackHead������
                callHandlerCallbackLater(newCtx, true);// ����һ���߳������Ժ�ִ�С�
                return this;
            }

            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                newCtx.setAddPending();
                // ��װ��������������������
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        
        // ����handlerState = ADD_COMPLETE״̬������handler�����handlerAdded����
        callHandlerAdded0(newCtx);
        return this;
    }

    // ���һ��context��pipline����(piplineĬ��ֻ��tail��head2���ڵ�)����ʵ����˫�� �������ӽڵ�Ĳ�����
    // ���˳��Ϊhead------------newCtx - tail;
    private void addLast0(AbstractChannelHandlerContext newCtx) {
        AbstractChannelHandlerContext prev = tail.prev;
        newCtx.prev = prev;
        newCtx.next = tail;
        prev.next = newCtx;
        tail.prev = newCtx;
    }

    @Override // ��ָ��AbstractChannelHandlerContext�� ֮ǰ��Ӵ�����
    public final ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler) {
        return addBefore(null, baseName, name, handler);
    }

    @Override
    public final ChannelPipeline addBefore(
            EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        final AbstractChannelHandlerContext ctx;
        synchronized (this) {
        	// �ж��Ƿ��Ѿ���ӹ�handler��
            checkMultiplicity(handler);
            
            // ��ȡ����������
            name = filterName(name, handler);
            
            // ��ȡ��׼AbstractChannelHandlerContext����
            ctx = getContextOrDie(baseName);

            // ����һ�� DefaultChannelHandlerContext����
            newCtx = newContext(group, name, handler);

            // ��newCtx�����ctxǰ��
            addBefore0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            if (!registered) {
            	// ����״̬ΪADD_PENDING
                newCtx.setAddPending();
                // ��AbstractChannelHandlerContext���������pendingHandlerCallbackHead������
                callHandlerCallbackLater(newCtx, true);
                return this;
            }

            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                newCtx.setAddPending();
                // ��װ��������������������
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        
        // ����handlerState = ADD_COMPLETE״̬������handler�����handlerAdded����
        callHandlerAdded0(newCtx);
        return this;
    }

    // ��newCtx�����ctxǰ��
    private static void addBefore0(AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        newCtx.prev = ctx.prev;
        newCtx.next = ctx;
        ctx.prev.next = newCtx;
        ctx.prev = newCtx;
    }

    //���nameΪ�գ�����һ������
    private String filterName(String name, ChannelHandler handler) {
        if (name == null) { 
            return generateName(handler);
        }
        //�ж������Ƿ��ظ�
        checkDuplicateName(name);
        return name;
    }

    @Override// ��baseName֮�����name
    public final ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler) {
        return addAfter(null, baseName, name, handler);
    }

    @Override
    public final ChannelPipeline addAfter(
            EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        final AbstractChannelHandlerContext ctx;

        synchronized (this) {
        	// �ж��Ƿ��Ѿ���ӹ�handler��
            checkMultiplicity(handler);
            
            // ��ȡ����������
            name = filterName(name, handler);
            
            // ��ȡ��׼AbstractChannelHandlerContext����
            ctx = getContextOrDie(baseName);

            // ����һ�� DefaultChannelHandlerContext����
            newCtx = newContext(group, name, handler);

            // ��newCtx�����ctx��
            addAfter0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we remove the context from the pipeline and add a task that will call
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            if (!registered) {
            	// ����״̬ΪADD_PENDING
                newCtx.setAddPending();
                // ��AbstractChannelHandlerContext���������pendingHandlerCallbackHead������
                callHandlerCallbackLater(newCtx, true);
                return this;
            }
            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
            	// ����״̬ΪADD_PENDING
                newCtx.setAddPending();
                // ��װ��������������������
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerAdded0(newCtx);
                    }
                });
                return this;
            }
        }
        // ����handlerState = ADD_COMPLETE״̬������handler�����handlerAdded����
        callHandlerAdded0(newCtx);
        return this;
    }

    // ��newCtx�����ctx��
    private static void addAfter0(AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        newCtx.prev = ctx;
        newCtx.next = ctx.next;
        ctx.next.prev = newCtx;
        ctx.next = newCtx;
    }

    @Override// ��handlers��ͷ��ʼ�����������
    public final ChannelPipeline addFirst(ChannelHandler... handlers) {
        return addFirst(null, handlers);
    }

    @Override// ��handlers��ͷ��ʼ�����������
    public final ChannelPipeline addFirst(EventExecutorGroup executor, ChannelHandler... handlers) {
        // ��ָ���쳣
    	if (handlers == null) {
            throw new NullPointerException("handlers");
        }
    	// �������handlersΪ��
        if (handlers.length == 0 || handlers[0] == null) {
            return this;
        }

        int size;
        for (size = 1; size < handlers.length; size ++) {
        	// ����handlers����
            if (handlers[size] == null) {
                break;
            }
        }

        // ����ChannelHandler����
        for (int i = size - 1; i >= 0; i --) {
            ChannelHandler h = handlers[i];
            // ��ChannelHandler�����������
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
        // ��handlersΪ��ʱ,�׳��쳣
    	if (handlers == null) {
            throw new NullPointerException("handlers");
        }

    	// ����ChannelHandler����
        for (ChannelHandler h: handlers) {
            if (h == null) {
                break;
            }
            // ��ChannelHandler�����������
            addLast(executor, null, h);
        }

        return this;
    }

    private String generateName(ChannelHandler handler) {
        Map<Class<?>, String> cache = nameCaches.get();
        
        // ��ȡhandler��Class����
        Class<?> handlerType = handler.getClass();
        // ��nameCaches�л�ȡhandlerType��Ӧ��nameֵ
        String name = cache.get(handlerType);
        if (name == null) {
        	// ��nameΪ��ʱ,name = className + "#0"
            name = generateName0(handlerType);
            cache.put(handlerType, name);
        }

        // It's not very likely for a user to put more than one handler of the same type, but make sure to avoid
        // any name conflicts.  Note that we don't cache the names generated here.
        if (context0(name) != null) {
        	// className + "#0"  == ȥ������0�ַ�
            String baseName = name.substring(0, name.length() - 1); // Strip the trailing '0'.
            for (int i = 1;; i ++) {
            	// newName = className + "#1"
                String newName = baseName + i;
                // ��newName���غ�ʱ,�˳�ѭ��
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
    	// ɾ��ָ����AbstractChannelHandlerContext����
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
        	// ��������ɾ��AbstractChannelHandlerContext����
            remove0(ctx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we remove the context from the pipeline and add a task that will call
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            // ��Ϊע��ʱ
            if (!registered) {
            	// ���PendingHandlerRemovedTask�������ɾ��AbstractChannelHandlerContext����
                callHandlerCallbackLater(ctx, false);
                return ctx;
            }

            EventExecutor executor = ctx.executor();
            if (!executor.inEventLoop()) {
            	// ��װ��������������������
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callHandlerRemoved0(ctx);
                    }
                });
                return ctx;
            }
        }
        // ��AbstractChannelHandlerContext��������ΪREMOVE_COMPLETE״̬,������handlrRemove����
        callHandlerRemoved0(ctx);
        return ctx;
    }

    // ��ִ��������ɾ��AbstractChannelHandlerContext���øı�ǰ��ָ��ָ��
    private static void remove0(AbstractChannelHandlerContext ctx) {
        AbstractChannelHandlerContext prev = ctx.prev;
        AbstractChannelHandlerContext next = ctx.next;
        prev.next = next;
        next.prev = prev;
    }

    @Override // ɾ��head��ĵ�һ��AbstractChannelHandlerContext����,��������Handler����
    public final ChannelHandler removeFirst() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(head.next).handler();
    }

    @Override// ɾ��tailǰ�ĵ�һ��AbstractChannelHandlerContext����,��������Handler����
    public final ChannelHandler removeLast() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(tail.prev).handler();
    }

    @Override// ��newHandler�滻oldHandler
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
        	// �����Ƿ��ظ����handler
            checkMultiplicity(newHandler);
            
            // �������������Ƿ���ͬ
            boolean sameName = ctx.name().equals(newName);
            if (!sameName) {
            	// ��������,��ֹ�����ͬ������
                checkDuplicateName(newName);
            }

            // ����AbstractChannelHandlerContext����
            newCtx = newContext(ctx.executor, newName, newHandler);

            replace0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we replace the context in the pipeline
            // and add a task that will call ChannelHandler.handlerAdded(...) and
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            if (!registered) {
            	// ����handlerAdded����
                callHandlerCallbackLater(newCtx, true);
                // ����handlerRemoved����
                callHandlerCallbackLater(ctx, false);
                // ����ctx�Ĵ�����handler
                return ctx.handler();
            }
            EventExecutor executor = ctx.executor();
            if (!executor.inEventLoop()) {
            	// ��װ���첽���������������
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Invoke newHandler.handlerAdded() first (i.e. before oldHandler.handlerRemoved() is invoked)
                        // because callHandlerRemoved() will trigger channelRead() or flush() on newHandler and
                        // those event handlers must be called after handlerAdded().
                    	// �첽����callHandlerAdded0��callHandlerRemoved0�¼�
                        callHandlerAdded0(newCtx);
                        callHandlerRemoved0(ctx);
                    }
                });
                // ����ctx�Ĵ�����handler
                return ctx.handler();
            }
        }
        // Invoke newHandler.handlerAdded() first (i.e. before oldHandler.handlerRemoved() is invoked)
        // because callHandlerRemoved() will trigger channelRead() or flush() on newHandler and those
        // event handlers must be called after handlerAdded().
        // ����callHandlerAdded0��callHandlerRemoved0�¼�
        callHandlerAdded0(newCtx);
        callHandlerRemoved0(ctx);
        // ����ctx�Ĵ�����handler
        return ctx.handler();
    }

    // ʹ��newCtx����oldCtx�������е�λ��
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

    // �����Ƿ��ظ����handler
    private static void checkMultiplicity(ChannelHandler handler) {
        if (handler instanceof ChannelHandlerAdapter) {
            ChannelHandlerAdapter h = (ChannelHandlerAdapter) handler;
            if (!h.isSharable() && h.added) { //���ǹ���ģ����ұ���ӹ�ֱ���׳��쳣
                throw new ChannelPipelineException(
                        h.getClass().getName() +
                        " is not a @Sharable handler, so can't be added or removed multiple times.");
            }
            h.added = true; //����added ��־λΪtrue
        }
    }

    // context����ӵ�pipline֮�����callHandlerAdded0
    private void callHandlerAdded0(final AbstractChannelHandlerContext ctx) {
        try {
            // We must call setAddComplete before calling handlerAdded. Otherwise if the handlerAdded method generates
            // any pipeline events ctx.handler() will miss them because the state will not allow it.
            ctx.setAddComplete();
            // ִ��handler��ӳɹ��ص�����
            ctx.handler().handlerAdded(ctx);
        } catch (Throwable t) {
            boolean removed = false;
            try {
            	// ��ִ��������ɾ����AbstractChannelHandlerContext
                remove0(ctx);
                try {
                	// �쳣ʱ,������Ӧ�Ĵ�����handlerRemoved
                    ctx.handler().handlerRemoved(ctx);
                } finally {
                	// �����Ƴ��ɹ�״̬
                    ctx.setRemoved();
                }
                removed = true;
            } catch (Throwable t2) {
            	// ��¼��־��
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to remove a handler: " + ctx.name(), t2);
                }
            }

            // ���ʧ�ܵ��ǳɹ�ɾ��
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
            	// ִ��Removed����
                ctx.handler().handlerRemoved(ctx);
            } finally {
            	// ��������ΪREMOVE_COMPLETE״̬
                ctx.setRemoved();
            }
        } catch (Throwable t) {
        	// �����쳣
            fireExceptionCaught(new ChannelPipelineException(
                    ctx.handler().getClass().getName() + ".handlerRemoved() has thrown an exception.", t));
        }
    }

    final void invokeHandlerAddedIfNeeded() {
        assert channel.eventLoop().inEventLoop();
        // �״�ע��ɹ�ʱ
        if (firstRegistration) {
        	// ����״̬
            firstRegistration = false;
            // We are now registered to the EventLoop. It's time to call the callbacks for the ChannelHandlers,
            // that were added before the registration was done.
            // �������е�pendingHandlerCallbackHead����
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

    @Override// ����handler��ȡChannelHandlerContext����
    public final ChannelHandlerContext context(ChannelHandler handler) {
    	// ��ָ���쳣
        if (handler == null) {
            throw new NullPointerException("handler");
        }

        AbstractChannelHandlerContext ctx = head.next;
        // ѭ����������������
        for (;;) {

            if (ctx == null) {
                return null;
            }

            // �����������ʱ,ֱ�ӷ���
            if (ctx.handler() == handler) {
                return ctx;
            }

            // ��ȡ��һ��ָ��
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

    @Override// �����������������������
    public final List<String> names() {
        List<String> list = new ArrayList<String>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == null) {
                return list;
            }
            // list��add����
            list.add(ctx.name());
            ctx = ctx.next;
        }
    }

    @Override// ��name��handler����һ��һ�Ĺ�ϵ
    public final Map<String, ChannelHandler> toMap() {
        Map<String, ChannelHandler> map = new LinkedHashMap<String, ChannelHandler>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == tail) {
                return map;
            }
            // put����,����ֵ�ԷŽ�Map��
            map.put(ctx.name(), ctx.handler());
            ctx = ctx.next;
        }
    }

    @Override// ��ȡ������
    public final Iterator<Map.Entry<String, ChannelHandler>> iterator() {
        return toMap().entrySet().iterator();
    }

    /**
     * Returns the {@link String} representation of this pipeline.
     */
    @Override // ����String����
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

    @Override// SocketChannelע���¼�����
    public final ChannelPipeline fireChannelRegistered() {
        AbstractChannelHandlerContext.invokeChannelRegistered(head);
        return this;
    }

    @Override// SocketChannelע���¼�����
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
    // �������е�handler����
    private synchronized void destroy() {
        destroyUp(head.next, false);
    }

    private void destroyUp(AbstractChannelHandlerContext ctx, boolean inEventLoop) {
        final Thread currentThread = Thread.currentThread();
        // ��ȡβ�ڵ�AbstractChannelHandlerContext����
        final AbstractChannelHandlerContext tail = this.tail;
        for (;;) {
        	// ��ctxΪ����β�ڵ�ʱ
            if (ctx == tail) {
                destroyDown(currentThread, tail.prev, inEventLoop);
                break;
            }

            final EventExecutor executor = ctx.executor();
            if (!inEventLoop && !executor.inEventLoop(currentThread)) {
                final AbstractChannelHandlerContext finalCtx = ctx;
                // ��װ���첽������������������ȥ
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
    	// ��ȡ����ͷ���
        final AbstractChannelHandlerContext head = this.head;
        for (;;) {
            if (ctx == head) {
                break;
            }

            // ��ȡ�߳�ִ����
            final EventExecutor executor = ctx.executor();
            if (inEventLoop || executor.inEventLoop(currentThread)) {
                synchronized (this) {
                	// ɾ����ǰAbstractChannelHandlerContext����
                    remove0(ctx);
                }
                // ִ�д�����HandlerRemoved()����
                callHandlerRemoved0(ctx);
            } else {
                final AbstractChannelHandlerContext finalCtx = ctx;
                // ����첽����
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

    @Override// ����ChannelActive�¼�
    public final ChannelPipeline fireChannelActive() {
        AbstractChannelHandlerContext.invokeChannelActive(head);
        return this;
    }

    @Override// ����ChannelInactive�¼�
    public final ChannelPipeline fireChannelInactive() {
        AbstractChannelHandlerContext.invokeChannelInactive(head);
        return this;
    }

    @Override// ����ExceptionCaught�¼�
    public final ChannelPipeline fireExceptionCaught(Throwable cause) {
        AbstractChannelHandlerContext.invokeExceptionCaught(head, cause);
        return this;
    }

    @Override// ����UserEventTriggered�¼�
    public final ChannelPipeline fireUserEventTriggered(Object event) {
        AbstractChannelHandlerContext.invokeUserEventTriggered(head, event);
        return this;
    }

    @Override// ����ChannelRead�¼�
    public final ChannelPipeline fireChannelRead(Object msg) {
        AbstractChannelHandlerContext.invokeChannelRead(head, msg);
        return this;
    }

    @Override// ����ChannelReadComplete�¼�
    public final ChannelPipeline fireChannelReadComplete() {
        AbstractChannelHandlerContext.invokeChannelReadComplete(head);
        return this;
    }

    @Override// ����ChannelWritabilityChanged�¼�
    public final ChannelPipeline fireChannelWritabilityChanged() {
        AbstractChannelHandlerContext.invokeChannelWritabilityChanged(head);
        return this;
    }

    @Override// ����bind�¼�
    public final ChannelFuture bind(SocketAddress localAddress) {
        return tail.bind(localAddress);
    }

    @Override// ����connect�¼�
    public final ChannelFuture connect(SocketAddress remoteAddress) {
        return tail.connect(remoteAddress);
    }

    @Override// ����connect�¼�
    public final ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return tail.connect(remoteAddress, localAddress);
    }

    @Override// ����disconnect�¼�
    public final ChannelFuture disconnect() {
        return tail.disconnect();
    }

    @Override// ����close�¼�
    public final ChannelFuture close() {
        return tail.close();
    }

    @Override// ����deregister�¼�
    public final ChannelFuture deregister() {
        return tail.deregister();
    }

    @Override// ����flush�¼�
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
    	// ��ֹ�ظ������ͬ��Handler
        if (context0(name) != null) {
            throw new IllegalArgumentException("Duplicate handler name: " + name);
        }
    }

    private AbstractChannelHandlerContext context0(String name) {
    	// ����ִ������
        AbstractChannelHandlerContext context = head.next;
        while (context != tail) {
        	// �鿴�������Ƿ��Ѿ�����ͬ��Handler
            if (context.name().equals(name)) {
                return context;
            }
            // ָ����һ������ڵ�
            context = context.next;
        }
        return null;
    }

    // �������ƻ�ȡAbstractChannelHandlerContext����
    private AbstractChannelHandlerContext getContextOrDie(String name) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(name);
        if (ctx == null) {
            throw new NoSuchElementException(name);
        } else {
            return ctx;
        }
    }

    // ��ȡhandler��Ӧ��AbstractChannelHandlerContext����
    private AbstractChannelHandlerContext getContextOrDie(ChannelHandler handler) {
    	// ����handler��ȡAbstractChannelHandlerContext����
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(handler);
        if (ctx == null) {
        	// ��ǰ�����в����ڸô�����,�׳��쳣!
            throw new NoSuchElementException(handler.getClass().getName());
        } else {
            return ctx;
        }
    }

    // ���ݴ��������ͻ�ȡAbstractChannelHandlerContext����
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

            // ��ȡpendingHandlerCallbackHead
            pendingHandlerCallbackHead = this.pendingHandlerCallbackHead;
            // Null out so it can be GC'ed.
            this.pendingHandlerCallbackHead = null;
        }

        // This must happen outside of the synchronized(...) block as otherwise handlerAdded(...) may be called while
        // holding the lock and so produce a deadlock if handlerAdded(...) will try to add another handler from outside
        // the EventLoop.
        PendingHandlerCallback task = pendingHandlerCallbackHead;
        // ִ���������
        while (task != null) {
        	// ִ��pendingHandlerCallbackHead����
            task.execute();
            task = task.next;
        }
    }

    private void callHandlerCallbackLater(AbstractChannelHandlerContext ctx, boolean added) {
    	// ��ǰ��δע����
        assert !registered;

        PendingHandlerCallback task = added ? new PendingHandlerAddedTask(ctx) : new PendingHandlerRemovedTask(ctx);
        // ��ȡͷ���ָ��
        PendingHandlerCallback pending = pendingHandlerCallbackHead;
        if (pending == null) {
            pendingHandlerCallbackHead = task;
        } else {
            // Find the tail of the linked-list.
        	// ��AbstractChannelHandlerContext���������ĩβ
            while (pending.next != null) {
                pending = pending.next;
            }
            // ��task���������ĩβ
            pending.next = task;
        }
    }

    /**
     * Called once a {@link Throwable} hit the end of the {@link ChannelPipeline} without been handled by the user
     * in {@link ChannelHandler#exceptionCaught(ChannelHandlerContext, Throwable)}.
     */
    protected void onUnhandledInboundException(Throwable cause) {
        try {
        	// ��־��¼
            logger.warn(
                    "An exceptionCaught() event was fired, and it reached at the tail of the pipeline. " +
                            "It usually means the last handler in the pipeline did not handle the exception.",
                    cause);
        } finally {
        	// ���ü����ݼ�
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
        	// ��־��¼
            logger.debug(
                    "Discarded inbound message {} that reached at the tail of the pipeline. " +
                            "Please check your pipeline configuration.", msg);
        } finally {
        	// ���ü����ݼ�
            ReferenceCountUtil.release(msg);
        }
    }

    // A special catch-all handler that handles both bytes and messages.
    // ��ջ������
    final class TailContext extends AbstractChannelHandlerContext implements ChannelInboundHandler {

        TailContext(DefaultChannelPipeline pipeline) {
            super(pipeline, null, TAIL_NAME, true, false);
            // ����������
            setAddComplete();
        }

        @Override// ��ȡthis
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
        	// ���ü����ݼ�
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

    // ��ջ������
    final class HeadContext extends AbstractChannelHandlerContext
            implements ChannelOutboundHandler, ChannelInboundHandler {

    	// ����SocketChannel�е�Unsafe��
        private final Unsafe unsafe;

        HeadContext(DefaultChannelPipeline pipeline) {
            super(pipeline, null, HEAD_NAME, false, true);
            unsafe = pipeline.channel().unsafe();
            setAddComplete();
        }

        @Override // ���ر���
        public ChannelHandler handler() {
            return this;
        }

        @Override// ������ʲô������
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
        	// ����unsafe.bind()����
            unsafe.bind(localAddress, promise);
        }

        @Override
        public void connect(
                ChannelHandlerContext ctx,
                SocketAddress remoteAddress, SocketAddress localAddress,
                ChannelPromise promise) throws Exception {
        	// unsafe����TCP��������
            unsafe.connect(remoteAddress, localAddress, promise);
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        	// unsafeȡ������
        	unsafe.disconnect(promise);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        	// unsafe�ر�����
            unsafe.close(promise);
        }

        @Override
        public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        	// unsafeע��
            unsafe.deregister(promise);
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
        	// unsafe���ö�����λ
            unsafe.beginRead();
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        	// unsafeд������
        	unsafe.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) throws Exception {
        	// unsafe����flush()
            unsafe.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        	// ����fireExceptionCaught�¼�
            ctx.fireExceptionCaught(cause);
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        	// ���ͨ�����Ƿ�����Ҫ�ӳ�ִ�е���������У���ִ�У�Ȼ����� Context�� fireChannelRegistered������
        	// ������ pipeline�� fireChannelRegistered����
            invokeHandlerAddedIfNeeded();
            ctx.fireChannelRegistered();
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelUnregistered();

            // Remove all handlers sequentially if channel is closed and unregistered.
            // ���SocketChannelɾ�����еĴ�����
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
        
        // ���ָ��ָ����һ��PendingHandlerCallback
        PendingHandlerCallback next;

        PendingHandlerCallback(AbstractChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        // ����ʵ��
        abstract void execute();
    }

    // �������������
    private final class PendingHandlerAddedTask extends PendingHandlerCallback {

    	// ����AbstractChannelHandlerContext����
        PendingHandlerAddedTask(AbstractChannelHandlerContext ctx) {
            super(ctx); // ָ��PendingHandlerCallback��
        }

        @Override
        public void run() {
            callHandlerAdded0(ctx);
        }

        @Override
        void execute() {
        	// ��ȡ�߳�ִ����
            EventExecutor executor = ctx.executor();
            if (executor.inEventLoop()) {
            	// ����callHandlerAdded0����
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
        	// ��ִ��������ɾ��AbstractChannelHandlerContext����
            callHandlerRemoved0(ctx);
        }

        @Override
        void execute() {
            EventExecutor executor = ctx.executor();
            if (executor.inEventLoop()) {
            	// ��ִ��������ɾ��AbstractChannelHandlerContext����
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
                    // ����״̬ΪREMOVE_COMPLETE
                    ctx.setRemoved();
                }
            }
        }
    }
}
