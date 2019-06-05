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

import io.netty.util.concurrent.AbstractFuture;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.concurrent.TimeUnit;

final class VoidChannelPromise extends AbstractFuture<Void> implements ChannelPromise {

    private final Channel channel;
    private final boolean fireException;

    /**
     * Creates a new instance.
     *
     * @param channel the {@link Channel} associated with this future
     */
    // 创建VoidChannelPromise对象
    VoidChannelPromise(Channel channel, boolean fireException) {
        if (channel == null) {
        	// 空指针异常
            throw new NullPointerException("channel");
        }
        // SocketChannel对象
        this.channel = channel;
        // 触发异常
        this.fireException = fireException;
    }

    @Override// 直接抛出IllegalStateException异常
    public VoidChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        fail();
        return this;
    }

    @Override// 直接抛出IllegalStateException异常
    public VoidChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        fail();
        return this;
    }

    @Override// 移除监听器,其实什么都不做
    public VoidChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        // NOOP
        return this;
    }

    @Override// 移除监听器,其实什么都不做
    public VoidChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        // NOOP
        return this;
    }

    @Override
    public VoidChannelPromise await() throws InterruptedException {
    	// interrupted:测试当前线程(当前线程是指运行interrupted()方法的线程)是否已经中断，且清除中断状态。
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return this;
    }

    @Override// 抛出IllegalStateException异常并返回false
    public boolean await(long timeout, TimeUnit unit) {
        fail();
        return false;
    }

    @Override// 抛出IllegalStateException异常并返回false
    public boolean await(long timeoutMillis) {
        fail();
        return false;
    }

    @Override// 抛出IllegalStateException异常
    public VoidChannelPromise awaitUninterruptibly() {
        fail();
        return this;
    }

    @Override// 抛出IllegalStateException异常并返回false
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        fail();
        return false;
    }

    @Override// 抛出IllegalStateException异常并返回false
    public boolean awaitUninterruptibly(long timeoutMillis) {
        fail();
        return false;
    }

    @Override// 返回SocketChannel对象
    public Channel channel() {
        return channel;
    }

    @Override// 是否已经处于完成状态中
    public boolean isDone() {
        return false;
    }

    @Override// 是否处于成功状态中
    public boolean isSuccess() {
        return false;
    }

    @Override// 设置为不可撤销状态并返回true
    public boolean setUncancellable() {
        return true;
    }

    @Override// 返回false状态
    public boolean isCancellable() {
        return false;
    }

    @Override // 返回false状态
    public boolean isCancelled() {
        return false;
    }

    @Override// 返回null异常
    public Throwable cause() {
        return null;
    }

    @Override// 抛出IllegalStateException异常
    public VoidChannelPromise sync() {
        fail();
        return this;
    }

    @Override// 抛出IllegalStateException异常
    public VoidChannelPromise syncUninterruptibly() {
        fail();
        return this;
    }
    @Override// 触发异常机制
    public VoidChannelPromise setFailure(Throwable cause) {
        fireException(cause);
        return this;
    }

    @Override
    public VoidChannelPromise setSuccess() {
        return this;
    }

    @Override // 触发异常
    public boolean tryFailure(Throwable cause) {
        fireException(cause);
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean trySuccess() {
        return false;
    }

    // 抛出IllegalStateException异常
    private static void fail() {
        throw new IllegalStateException("void future");
    }

    @Override
    public VoidChannelPromise setSuccess(Void result) {
        return this;
    }

    @Override // 返回false
    public boolean trySuccess(Void result) {
        return false;
    }

    @Override
    public Void getNow() {
        return null;
    }

    // 触发异常调用机制
    private void fireException(Throwable cause) {
        // Only fire the exception if the channel is open and registered
        // if not the pipeline is not setup and so it would hit the tail
        // of the pipeline.
        // See https://github.com/netty/netty/issues/1517
    	// 当fireException为true时,SocketChannel已经注册过的
        if (fireException && channel.isRegistered()) {
            channel.pipeline().fireExceptionCaught(cause);
        }
    }
}
