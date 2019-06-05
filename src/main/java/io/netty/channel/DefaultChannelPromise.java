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

	// ����TCP�շ������ͨ������
    private final Channel channel;
    private long checkpoint;

    /**
     * Creates a new instance.
     *
     * @param channel
     *        the {@link Channel} associated with this future
     */
    public DefaultChannelPromise(Channel channel) {
    	// ����channel�ֶ�
        this.channel = checkNotNull(channel, "channel");
    }

    /**
     * Creates a new instance.
     *
     * @param channel
     *        the {@link Channel} associated with this future
     */
    // Promise������,ʵ���첽����
    public DefaultChannelPromise(Channel channel, EventExecutor executor) {
        super(executor); // --> DefaultPromise��
        this.channel = checkNotNull(channel, "channel");
    }

    @Override
    protected EventExecutor executor() {
    	// �����߳�ִ����
        EventExecutor e = super.executor();
        if (e == null) {
        	// ����SocketChannel��ȡ�߳�ִ����
            return channel().eventLoop();
        } else {
            return e;
        }
    }

    @Override
    public Channel channel() {
    	// ����channel�ֶ�
        return channel;
    }

    @Override
    public ChannelPromise setSuccess() {
        return setSuccess(null);
    }

    @Override// ���óɹ�
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
        super.setFailure(cause); // --> DefaultPromise��
        return this;
    }

    @Override// ��Ӽ�����
    public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.addListener(listener);// --> DefaultPromise��
        return this;
    }

    @Override
    public ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.addListeners(listeners);// --> DefaultPromise��
        return this;
    }

    @Override// �Ƴ�������
    public ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.removeListener(listener);// --> DefaultPromise��
        return this;
    }

    @Override// �Ƴ�������
    public ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.removeListeners(listeners);// --> DefaultPromise��
        return this;
    }

    @Override// ͬ������
    public ChannelPromise sync() throws InterruptedException {
        super.sync();// --> DefaultPromise��
        return this;
    }

    @Override
    public ChannelPromise syncUninterruptibly() {
        super.syncUninterruptibly();// --> DefaultPromise��
        return this;
    }

    @Override
    public ChannelPromise await() throws InterruptedException {
        super.await();// --> DefaultPromise��
        return this;
    }

    @Override
    public ChannelPromise awaitUninterruptibly() {
        super.awaitUninterruptibly();// --> DefaultPromise��
        return this;
    }

    @Override// ����checkpoint����
    public long flushCheckpoint() {
        return checkpoint;
    }

    @Override// ����checkpoint����
    public void flushCheckpoint(long checkpoint) {
        this.checkpoint = checkpoint;
    }

    @Override
    public ChannelPromise promise() {
        return this;
    }

    @Override// �������
    protected void checkDeadLock() {
        if (channel().isRegistered()) {
            super.checkDeadLock();// --> DefaultPromise��
        }
    }
}
