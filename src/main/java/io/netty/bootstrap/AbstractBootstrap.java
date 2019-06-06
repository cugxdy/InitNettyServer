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

	// 线程组, volatile = 可见性 、有序性
    volatile EventLoopGroup group;
    
    // channel工厂
    private volatile ChannelFactory<? extends C> channelFactory;
    
    // 客户端地址
    private volatile SocketAddress localAddress;
    
    // NioServerSocketChannel实例的相关配置选项
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();
    
    // NioServerSocketChannel实例的相关配置选项
    private final Map<AttributeKey<?>, Object> attrs = new LinkedHashMap<AttributeKey<?>, Object>();
    
    // 客户端socket执行链
    private volatile ChannelHandler handler;

    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    
    // 私有构造函数调用结果.
    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        
        // 同步操作
        synchronized (bootstrap.options) {
            options.putAll(bootstrap.options);
        }
        // 同步操作
        synchronized (bootstrap.attrs) {
            attrs.putAll(bootstrap.attrs);
        }
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created
     * {@link Channel}
     */
    // 设置I/O线程组
    public B group(EventLoopGroup group) {
    	// group不存在时
        if (group == null) {
            throw new NullPointerException("group");
        }
        // 防止重复设置
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        // 设置线程组
        this.group = group;
        return self();
    }

    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this; // 返回自身
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     */
    // 指定server|client所使用的channel接口，对于TCP服务端->NioServerSocketChannel,对于TCP客户端->NioSocketChannel
    public B channel(Class<? extends C> channelClass) {
    	// 当channelClass不存在时
        if (channelClass == null) {
        	// 抛出NullPointerException异常
            throw new NullPointerException("channelClass");
        }
        // 实例化channelClass对象
        return channelFactory(new BootstrapChannelFactory<C>(channelClass));
    }

    /**
     * {@link ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} for
     * simplify your code.
     */
    // 设置AbstractBootstrap类的字段channelFactory
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
    	// 空指针异常
        if (channelFactory == null) {
            throw new NullPointerException("channelFactory");
        }
        // 防止重复设置
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }
        // 设置channel工厂
        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     *
     */
    // 设置本地服务地址
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
    	// 创建InetSocketAddress对象
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
    	// 空指针异常
        if (option == null) {
            throw new NullPointerException("option");
        }
        // 当value为空时,即为删除操作
        if (value == null) {
            synchronized (options) {
            	// 删除相应key(option)
                options.remove(option);
            }
        } else {
            synchronized (options) {
            	// 将键值对放入到options中去
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
    // 检验相应字段是否存在。
    public B validate() {
    	// 检验相应的字段group|channelFactory是否为空
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
    // 由子类实现
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    // 注册操作
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    // 绑定到指定服务地址上
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
        // 空指针异常
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        return doBind(localAddress);
    }

    // 服务端绑定本地服务的地址函数
    private ChannelFuture doBind(final SocketAddress localAddress) {
    	
    	// initAndRegister初始化 NioServerSocketChannel通道并注册各个 handler,返回一个 future。
        final ChannelFuture regFuture = initAndRegister();
        // 获取创建的Channel
        final Channel channel = regFuture.channel();
        // 判断异步操作过程中是否抛出异常
        if (regFuture.cause() != null) {
            return regFuture;
        }

        // 判断异步操作是否完成
        if (regFuture.isDone()) {
            // At this point we know that the registration was complete and successful.
            ChannelPromise promise = channel.newPromise();
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            // 监视器操作
            regFuture.addListener(new ChannelFutureListener() {
                @Override // 当完成时调用该函数
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    // 执行失败时
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.executor = channel.eventLoop();

                        // 执行 doBind0 方法，完成对端口的绑定。
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
        	// 通过 ServerBootstrap 的通道工厂反射创建一个 NioServerSocketChannel。
            channel = channelFactory().newChannel();
            // 配置TCP参数
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

        // 将创建Channel注册至selector轮询队列上
        ChannelFuture regFuture = group().register(channel);
        // 判断异步操作过程中是否抛出异常
        if (regFuture.cause() != null) {
        	// 判断Channel是否已经注册
            if (channel.isRegistered()) {
                channel.close(); // 关闭相应的Channel
            } else {
            	// 调用Channel内部类unsafe进行关闭操作
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

    // 由子类实现
    abstract void init(Channel channel) throws Exception;

    // 服务端绑定本地地址并监听的方法
    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
            	// 异步操作成功时,调用bind方法绑定服务器本地地址
                if (regFuture.isSuccess()) {
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                	// 设置失败通知操作
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     */
    // Bootstrap为了简化Handler的编排,提供了ChannelInitializer,它继承了ChannelHandlerAdapter,
    // 当TCP链路注册成功之后,调用initChannel接口,用于设置用户的ChannelHandler
    public B handler(ChannelHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        return self();
    }

    // 获取本地地址
    final SocketAddress localAddress() {
        return localAddress;
    }

    // 获取channelFactory字段
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    // 获取Socket执行链
    final ChannelHandler handler() {
        return handler;
    }

    /**
     * Return the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     */
    // 获取线程组
    public EventLoopGroup group() {
        return group;
    }

    // 获取options属性
    final Map<ChannelOption<?>, Object> options() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return attrs;
    }

    // 设置NioServerSocketChannel相应属性
    static void setChannelOptions(
            Channel channel, Map<ChannelOption<?>, Object> options, InternalLogger logger) {
    	// 对Map进行遍历设置相应option:option(ChannelOption.SO_BACKLOG, 100)
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
        	// 检查NioServerSocketChannel的配置选项并进行设置
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
            	// 记录日志警告
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    
    // 重写toString()方法
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
        // 同步代码块的操作
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
        // Channel工厂类
    	private final Class<? extends T> clazz;

        // 初始化class对象
        BootstrapChannelFactory(Class<? extends T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public T newChannel() {
            try {
            	// 通过构造器生成实例对象
                return clazz.getConstructor().newInstance();
            } catch (Throwable t) {
                throw new ChannelException("Unable to create Channel from class " + clazz, t);
            }
        }

        @Override
        public String toString() {
        	// 覆写toString方法
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
