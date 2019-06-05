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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A {@link Bootstrap} that makes it easy to bootstrap a {@link Channel} to use
 * for clients.
 *
 * <p>The {@link #bind()} methods are useful in combination with connectionless transports such as datagram (UDP).
 * For regular TCP connections, please use the provided {@link #connect()} methods.</p>
 */
public class Bootstrap extends AbstractBootstrap<Bootstrap, Channel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Bootstrap.class);

    // 远程服务地址
    private volatile SocketAddress remoteAddress;

    public Bootstrap() { }

    private Bootstrap(Bootstrap bootstrap) {
        super(bootstrap); // 跳转至AbstractBootstrap类
        remoteAddress = bootstrap.remoteAddress;
    }

    /**
     * The {@link SocketAddress} to connect to once the {@link #connect()} method
     * is called.
     */
    // 设置remoteAddress字段
    public Bootstrap remoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(String inetHost, int inetPort) {
    	// 创建InetSocketAddress远程主机地址
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(InetAddress inetHost, int inetPort) {
    	// 创建InetSocketAddress远程主机地址
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect() {
    	// 检验相应的字段group|channelFactory是否为空
        validate();
        SocketAddress remoteAddress = this.remoteAddress;
        // remoteAddress不为空时
        if (remoteAddress == null) {
            throw new IllegalStateException("remoteAddress not set");
        }

        return doConnect(remoteAddress, localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    // 连接远程主机服务
    public ChannelFuture connect(String inetHost, int inetPort) {
        return connect(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(InetAddress inetHost, int inetPort) {
        return connect(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    // 根据参数remoteAddress连接远程主机服务地址
    public ChannelFuture connect(SocketAddress remoteAddress) {
    	// 空指针异常
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }

        // 检验相应字段是否存在
        validate();
        return doConnect(remoteAddress, localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }
        // 检验相应字段是否存在
        validate();
        return doConnect(remoteAddress, localAddress);
    }

    /**
     * @see #connect()
     */
    // 向远程主机发起Tcp连接请求remoteAddress:远程主机地址  localAddress:本地主机地址
    private ChannelFuture doConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
        // 创建以及初始化NioSocketChannel
    	final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();
        // 无异常发生
        if (regFuture.cause() != null) {
            return regFuture;
        }

        final ChannelPromise promise = channel.newPromise();
        // 是否操作完成
        if (regFuture.isDone()) {
            doConnect0(regFuture, channel, remoteAddress, localAddress, promise);
        } else {
        	// 添加监视器
            regFuture.addListener(new ChannelFutureListener() {
                @Override // 操作完成后调用该方法
                public void operationComplete(ChannelFuture future) throws Exception {
                    doConnect0(regFuture, channel, remoteAddress, localAddress, promise);
                }
            });
        }

        return promise;
    }

    private static void doConnect0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
    	// 链路创建成功后，发起异步的Tcp连接
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
            	// 判断regFuture是否处于成功状态中
                if (regFuture.isSuccess()) {
                	// 依据是否存在localAddress地址调用不同的函数
                    if (localAddress == null) {
                        channel.connect(remoteAddress, promise);
                    } else {
                        channel.connect(remoteAddress, localAddress, promise);
                    }
                    // 设置监听器,当connect未完成时触发operationComplete事件,关闭SocketChannel
                    promise.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    void init(Channel channel) throws Exception {
    	// 客户端获取pipeline属性
        ChannelPipeline p = channel.pipeline();
        // 配置职责链
        p.addLast(handler());

        // 设置Channel所配置的属性
        final Map<ChannelOption<?>, Object> options = options();
        synchronized (options) {
        	// 设置SocketChannel配置选项
            setChannelOptions(channel, options, logger);
        }

        final Map<AttributeKey<?>, Object> attrs = attrs();
        synchronized (attrs) {
            for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
            	// 设置SocketChannel配置属性
                channel.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }
        }
    }

    // 验证当前Bootstroop
    @Override
    public Bootstrap validate() {
        super.validate(); // --> AbstractBootstrap类
        if (handler() == null) {
            throw new IllegalStateException("handler not set");
        }
        return this;
    }

    // 复制一个新的Bootstroop对象
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public Bootstrap clone() {
        return new Bootstrap(this);// 调用私有构造函数
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration except that it uses
     * the given {@link EventLoopGroup}. This method is useful when making multiple {@link Channel}s with similar
     * settings.
     */
    // 创建Bootstrap对象
    public Bootstrap clone(EventLoopGroup group) {
        Bootstrap bs = new Bootstrap(this);
        bs.group = group;
        return bs;
    }

    
    @Override// 返回String对象
    public String toString() {
        if (remoteAddress == null) {
            return super.toString(); // 调用父类方法
        }

        StringBuilder buf = new StringBuilder(super.toString());
        buf.setLength(buf.length() - 1);

        return buf.append(", remoteAddress: ")
                  .append(remoteAddress)
                  .append(')')
                  .toString();
    }
}
