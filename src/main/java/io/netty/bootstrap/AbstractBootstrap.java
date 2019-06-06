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

package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.internal.SocketUtils;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link AbstractBootstrap} is a helper class that makes it easy to bootstrap a {@link Channel}. It support
 * method-chaining to provide an easy way to configure the {@link AbstractBootstrap}.
 *
 * <p>When not used in a {@link ServerBootstrap} context, the {@link #bind()} methods are useful for connectionless
 * transports such as datagram (UDP).</p>
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

	// �߳���, volatile = �ɼ��� ��������
    volatile EventLoopGroup group;
    
    // channel����
    private volatile ChannelFactory<? extends C> channelFactory;
    
    // �ͻ��˵�ַ
    private volatile SocketAddress localAddress;
    
    // NioServerSocketChannelʵ�����������ѡ��
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();
    
    // NioServerSocketChannelʵ�����������ѡ��
    private final Map<AttributeKey<?>, Object> attrs = new LinkedHashMap<AttributeKey<?>, Object>();
    
    // �ͻ���socketִ����
    private volatile ChannelHandler handler;

    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    
    // ˽�й��캯�����ý��.
    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        
        // ͬ������
        synchronized (bootstrap.options) {
            options.putAll(bootstrap.options);
        }
        // ͬ������
        synchronized (bootstrap.attrs) {
            attrs.putAll(bootstrap.attrs);
        }
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created
     * {@link Channel}
     */
    // ����I/O�߳���
    public B group(EventLoopGroup group) {
    	// group������ʱ
        if (group == null) {
            throw new NullPointerException("group");
        }
        // ��ֹ�ظ�����
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        // �����߳���
        this.group = group;
        return self();
    }

    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this; // ��������
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     */
    // ָ��server|client��ʹ�õ�channel�ӿڣ�����TCP�����->NioServerSocketChannel,����TCP�ͻ���->NioSocketChannel
    public B channel(Class<? extends C> channelClass) {
    	// ��channelClass������ʱ
        if (channelClass == null) {
        	// �׳�NullPointerException�쳣
            throw new NullPointerException("channelClass");
        }
        // ʵ����channelClass����
        return channelFactory(new BootstrapChannelFactory<C>(channelClass));
    }

    /**
     * {@link ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} for
     * simplify your code.
     */
    // ����AbstractBootstrap����ֶ�channelFactory
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
    	// ��ָ���쳣
        if (channelFactory == null) {
            throw new NullPointerException("channelFactory");
        }
        // ��ֹ�ظ�����
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }
        // ����channel����
        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     *
     */
    // ���ñ��ط����ַ
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
    	// ����InetSocketAddress����
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     */
    public <T> B option(ChannelOption<T> option, T value) {
    	// ��ָ���쳣
        if (option == null) {
            throw new NullPointerException("option");
        }
        // ��valueΪ��ʱ,��Ϊɾ������
        if (value == null) {
            synchronized (options) {
            	// ɾ����Ӧkey(option)
                options.remove(option);
            }
        } else {
            synchronized (options) {
            	// ����ֵ�Է��뵽options��ȥ
                options.put(option, value);
            }
        }
        return self();
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (value == null) {
            synchronized (attrs) {
                attrs.remove(key);
            }
        } else {
            synchronized (attrs) {
                attrs.put(key, value);
            }
        }
        return self();
    }

    /**
     * Validate all the parameters. Sub-classes may override this, but should
     * call the super method in that case.
     */
    // ������Ӧ�ֶ��Ƿ���ڡ�
    public B validate() {
    	// ������Ӧ���ֶ�group|channelFactory�Ƿ�Ϊ��
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     */
    // ������ʵ��
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    // ע�����
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    // �󶨵�ָ�������ַ��
    public ChannelFuture bind() {
    	
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        validate();
        // ��ָ���쳣
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        return doBind(localAddress);
    }

    // ����˰󶨱��ط���ĵ�ַ����
    private ChannelFuture doBind(final SocketAddress localAddress) {
    	
    	// initAndRegister��ʼ�� NioServerSocketChannelͨ����ע����� handler,����һ�� future��
        final ChannelFuture regFuture = initAndRegister();
        // ��ȡ������Channel
        final Channel channel = regFuture.channel();
        // �ж��첽�����������Ƿ��׳��쳣
        if (regFuture.cause() != null) {
            return regFuture;
        }

        // �ж��첽�����Ƿ����
        if (regFuture.isDone()) {
            // At this point we know that the registration was complete and successful.
            ChannelPromise promise = channel.newPromise();
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            // ����������
            regFuture.addListener(new ChannelFutureListener() {
                @Override // �����ʱ���øú���
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    // ִ��ʧ��ʱ
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.executor = channel.eventLoop();

                        // ִ�� doBind0 ��������ɶԶ˿ڵİ󶨡�
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
        	// ͨ�� ServerBootstrap ��ͨ���������䴴��һ�� NioServerSocketChannel��
            channel = channelFactory().newChannel();
            // ����TCP����
            init(channel);
        } catch (Throwable t) {
            if (channel != null) {
                // channel can be null if newChannel crashed (eg SocketException("too many open files"))
                channel.unsafe().closeForcibly();
                // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }

        // ������Channelע����selector��ѯ������
        ChannelFuture regFuture = group().register(channel);
        // �ж��첽�����������Ƿ��׳��쳣
        if (regFuture.cause() != null) {
        	// �ж�Channel�Ƿ��Ѿ�ע��
            if (channel.isRegistered()) {
                channel.close(); // �ر���Ӧ��Channel
            } else {
            	// ����Channel�ڲ���unsafe���йرղ���
                channel.unsafe().closeForcibly();
            }
        }

        // If we are here and the promise is not failed, it's one of the following cases:
        // 1) If we attempted registration from the event loop, the registration has been completed at this point.
        //    i.e. It's safe to attempt bind() or connect() now because the channel has been registered.
        // 2) If we attempted registration from the other thread, the registration request has been successfully
        //    added to the event loop's task queue for later execution.
        //    i.e. It's safe to attempt bind() or connect() now:
        //         because bind() or connect() will be executed *after* the scheduled registration task is executed
        //         because register(), bind(), and connect() are all bound to the same thread.

        return regFuture;
    }

    // ������ʵ��
    abstract void init(Channel channel) throws Exception;

    // ����˰󶨱��ص�ַ�������ķ���
    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
            	// �첽�����ɹ�ʱ,����bind�����󶨷��������ص�ַ
                if (regFuture.isSuccess()) {
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                	// ����ʧ��֪ͨ����
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     */
    // BootstrapΪ�˼�Handler�ı���,�ṩ��ChannelInitializer,���̳���ChannelHandlerAdapter,
    // ��TCP��·ע��ɹ�֮��,����initChannel�ӿ�,���������û���ChannelHandler
    public B handler(ChannelHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        return self();
    }

    // ��ȡ���ص�ַ
    final SocketAddress localAddress() {
        return localAddress;
    }

    // ��ȡchannelFactory�ֶ�
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    // ��ȡSocketִ����
    final ChannelHandler handler() {
        return handler;
    }

    /**
     * Return the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     */
    // ��ȡ�߳���
    public EventLoopGroup group() {
        return group;
    }

    // ��ȡoptions����
    final Map<ChannelOption<?>, Object> options() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return attrs;
    }

    // ����NioServerSocketChannel��Ӧ����
    static void setChannelOptions(
            Channel channel, Map<ChannelOption<?>, Object> options, InternalLogger logger) {
    	// ��Map���б���������Ӧoption:option(ChannelOption.SO_BACKLOG, 100)
        for (Map.Entry<ChannelOption<?>, Object> e: options.entrySet()) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
        	// ���NioServerSocketChannel������ѡ���������
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
            	// ��¼��־����
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    
    // ��дtoString()����
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(');
        if (group != null) {
            buf.append("group: ")
               .append(StringUtil.simpleClassName(group))
               .append(", ");
        }
        if (channelFactory != null) {
            buf.append("channelFactory: ")
               .append(channelFactory)
               .append(", ");
        }
        if (localAddress != null) {
            buf.append("localAddress: ")
               .append(localAddress)
               .append(", ");
        }
        synchronized (options) {
            if (!options.isEmpty()) {
                buf.append("options: ")
                   .append(options)
                   .append(", ");
            }
        }
        // ͬ�������Ĳ���
        synchronized (attrs) {
            if (!attrs.isEmpty()) {
                buf.append("attrs: ")
                   .append(attrs)
                   .append(", ");
            }
        }
        if (handler != null) {
            buf.append("handler: ")
               .append(handler)
               .append(", ");
        }
        if (buf.charAt(buf.length() - 1) == '(') {
            buf.append(')');
        } else {
            buf.setCharAt(buf.length() - 2, ')');
            buf.setLength(buf.length() - 1);
        }
        return buf.toString();
    }

    private static final class BootstrapChannelFactory<T extends Channel> implements ChannelFactory<T> {
        // Channel������
    	private final Class<? extends T> clazz;

        // ��ʼ��class����
        BootstrapChannelFactory(Class<? extends T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public T newChannel() {
            try {
            	// ͨ������������ʵ������
                return clazz.getConstructor().newInstance();
            } catch (Throwable t) {
                throw new ChannelException("Unable to create Channel from class " + clazz, t);
            }
        }

        @Override
        public String toString() {
        	// ��дtoString����
            return StringUtil.simpleClassName(clazz) + ".class";
        }
    }

    private static final class PendingRegistrationPromise extends DefaultChannelPromise {
        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        private volatile EventExecutor executor;

        private PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        @Override
        protected EventExecutor executor() {
            EventExecutor executor = this.executor;
            if (executor != null) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return executor;
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}
