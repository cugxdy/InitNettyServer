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

    // Զ�̷����ַ
    private volatile SocketAddress remoteAddress;

    public Bootstrap() { }

    private Bootstrap(Bootstrap bootstrap) {
        super(bootstrap); // ��ת��AbstractBootstrap��
        remoteAddress = bootstrap.remoteAddress;
    }

    /**
     * The {@link SocketAddress} to connect to once the {@link #connect()} method
     * is called.
     */
    // ����remoteAddress�ֶ�
    public Bootstrap remoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(String inetHost, int inetPort) {
    	// ����InetSocketAddressԶ��������ַ
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(InetAddress inetHost, int inetPort) {
    	// ����InetSocketAddressԶ��������ַ
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect() {
    	// ������Ӧ���ֶ�group|channelFactory�Ƿ�Ϊ��
        validate();
        SocketAddress remoteAddress = this.remoteAddress;
        // remoteAddress��Ϊ��ʱ
        if (remoteAddress == null) {
            throw new IllegalStateException("remoteAddress not set");
        }

        return doConnect(remoteAddress, localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    // ����Զ����������
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
    // ���ݲ���remoteAddress����Զ�����������ַ
    public ChannelFuture connect(SocketAddress remoteAddress) {
    	// ��ָ���쳣
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }

        // ������Ӧ�ֶ��Ƿ����
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
        // ������Ӧ�ֶ��Ƿ����
        validate();
        return doConnect(remoteAddress, localAddress);
    }

    /**
     * @see #connect()
     */
    // ��Զ����������Tcp��������remoteAddress:Զ��������ַ  localAddress:����������ַ
    private ChannelFuture doConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
        // �����Լ���ʼ��NioSocketChannel
    	final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();
        // ���쳣����
        if (regFuture.cause() != null) {
            return regFuture;
        }

        final ChannelPromise promise = channel.newPromise();
        // �Ƿ�������
        if (regFuture.isDone()) {
            doConnect0(regFuture, channel, remoteAddress, localAddress, promise);
        } else {
        	// ��Ӽ�����
            regFuture.addListener(new ChannelFutureListener() {
                @Override // ������ɺ���ø÷���
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
    	// ��·�����ɹ��󣬷����첽��Tcp����
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
            	// �ж�regFuture�Ƿ��ڳɹ�״̬��
                if (regFuture.isSuccess()) {
                	// �����Ƿ����localAddress��ַ���ò�ͬ�ĺ���
                    if (localAddress == null) {
                        channel.connect(remoteAddress, promise);
                    } else {
                        channel.connect(remoteAddress, localAddress, promise);
                    }
                    // ���ü�����,��connectδ���ʱ����operationComplete�¼�,�ر�SocketChannel
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
    	// �ͻ��˻�ȡpipeline����
        ChannelPipeline p = channel.pipeline();
        // ����ְ����
        p.addLast(handler());

        // ����Channel�����õ�����
        final Map<ChannelOption<?>, Object> options = options();
        synchronized (options) {
        	// ����SocketChannel����ѡ��
            setChannelOptions(channel, options, logger);
        }

        final Map<AttributeKey<?>, Object> attrs = attrs();
        synchronized (attrs) {
            for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
            	// ����SocketChannel��������
                channel.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }
        }
    }

    // ��֤��ǰBootstroop
    @Override
    public Bootstrap validate() {
        super.validate(); // --> AbstractBootstrap��
        if (handler() == null) {
            throw new IllegalStateException("handler not set");
        }
        return this;
    }

    // ����һ���µ�Bootstroop����
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public Bootstrap clone() {
        return new Bootstrap(this);// ����˽�й��캯��
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration except that it uses
     * the given {@link EventLoopGroup}. This method is useful when making multiple {@link Channel}s with similar
     * settings.
     */
    // ����Bootstrap����
    public Bootstrap clone(EventLoopGroup group) {
        Bootstrap bs = new Bootstrap(this);
        bs.group = group;
        return bs;
    }

    
    @Override// ����String����
    public String toString() {
        if (remoteAddress == null) {
            return super.toString(); // ���ø��෽��
        }

        StringBuilder buf = new StringBuilder(super.toString());
        buf.setLength(buf.length() - 1);

        return buf.append(", remoteAddress: ")
                  .append(remoteAddress)
                  .append(')')
                  .toString();
    }
}
