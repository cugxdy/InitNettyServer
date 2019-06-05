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

import io.netty.channel.ChannelFlushPromiseNotifier.FlushCheckpoint;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * The default {@link ChannelPromise} implementation.  It is recommended to use {@link Channel#newPromise()} to create
 * a new {@link ChannelPromise} rather than calling the constructor explicitly.
 */
public class DefaultChannelPromise extends DefaultPromise<Void> implements ChannelPromise, FlushCheckpoint {

	// 用于TCP收发请求的通道连接
    private final Channel channel;
    private long checkpoint;

    /**
     * Creates a new instance.
     *
     * @param channel
     *        the {@link Channel} associated with this future
     */
    public DefaultChannelPromise(Channel channel) {
    	// 设置channel字段
        this.channel = checkNotNull(channel, "channel");
    }

    /**
     * Creates a new instance.
     *
     * @param channel
     *        the {@link Channel} associated with this future
     */
    // Promise的作用,实现异步操作
    public DefaultChannelPromise(Channel channel, EventExecutor executor) {
        super(executor); // --> DefaultPromise类
        this.channel = checkNotNull(channel, "channel");
    }

    @Override
    protected EventExecutor executor() {
    	// 返回线程执行器
        EventExecutor e = super.executor();
        if (e == null) {
        	// 依据SocketChannel获取线程执行器
            return channel().eventLoop();
        } else {
            return e;
        }
    }

    @Override
    public Channel channel() {
    	// 返回channel字段
        return channel;
    }

    @Override
    public ChannelPromise setSuccess() {
        return setSuccess(null);
    }

    @Override// 设置成功
    public ChannelPromise setSuccess(Void result) {
        super.setSuccess(result);
        return this;
    }

    @Override
    public boolean trySuccess() {
        return trySuccess(null);
    }

    @Override
    public ChannelPromise setFailure(Throwable cause) {
        super.setFailure(cause); // --> DefaultPromise类
        return this;
    }

    @Override// 添加监听者
    public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.addListener(listener);// --> DefaultPromise类
        return this;
    }

    @Override
    public ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.addListeners(listeners);// --> DefaultPromise类
        return this;
    }

    @Override// 移除监听器
    public ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.removeListener(listener);// --> DefaultPromise类
        return this;
    }

    @Override// 移除监听器
    public ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.removeListeners(listeners);// --> DefaultPromise类
        return this;
    }

    @Override// 同步操作
    public ChannelPromise sync() throws InterruptedException {
        super.sync();// --> DefaultPromise类
        return this;
    }

    @Override
    public ChannelPromise syncUninterruptibly() {
        super.syncUninterruptibly();// --> DefaultPromise类
        return this;
    }

    @Override
    public ChannelPromise await() throws InterruptedException {
        super.await();// --> DefaultPromise类
        return this;
    }

    @Override
    public ChannelPromise awaitUninterruptibly() {
        super.awaitUninterruptibly();// --> DefaultPromise类
        return this;
    }

    @Override// 返回checkpoint属性
    public long flushCheckpoint() {
        return checkpoint;
    }

    @Override// 设置checkpoint属性
    public void flushCheckpoint(long checkpoint) {
        this.checkpoint = checkpoint;
    }

    @Override
    public ChannelPromise promise() {
        return this;
    }

    @Override// 检测死锁
    protected void checkDeadLock() {
        if (channel().isRegistered()) {
            super.checkDeadLock();// --> DefaultPromise类
        }
    }
}
