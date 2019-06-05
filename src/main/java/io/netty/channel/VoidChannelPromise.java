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
    // ����VoidChannelPromise����
    VoidChannelPromise(Channel channel, boolean fireException) {
        if (channel == null) {
        	// ��ָ���쳣
            throw new NullPointerException("channel");
        }
        // SocketChannel����
        this.channel = channel;
        // �����쳣
        this.fireException = fireException;
    }

    @Override// ֱ���׳�IllegalStateException�쳣
    public VoidChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        fail();
        return this;
    }

    @Override// ֱ���׳�IllegalStateException�쳣
    public VoidChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        fail();
        return this;
    }

    @Override// �Ƴ�������,��ʵʲô������
    public VoidChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        // NOOP
        return this;
    }

    @Override// �Ƴ�������,��ʵʲô������
    public VoidChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        // NOOP
        return this;
    }

    @Override
    public VoidChannelPromise await() throws InterruptedException {
    	// interrupted:���Ե�ǰ�߳�(��ǰ�߳���ָ����interrupted()�������߳�)�Ƿ��Ѿ��жϣ�������ж�״̬��
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return this;
    }

    @Override// �׳�IllegalStateException�쳣������false
    public boolean await(long timeout, TimeUnit unit) {
        fail();
        return false;
    }

    @Override// �׳�IllegalStateException�쳣������false
    public boolean await(long timeoutMillis) {
        fail();
        return false;
    }

    @Override// �׳�IllegalStateException�쳣
    public VoidChannelPromise awaitUninterruptibly() {
        fail();
        return this;
    }

    @Override// �׳�IllegalStateException�쳣������false
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        fail();
        return false;
    }

    @Override// �׳�IllegalStateException�쳣������false
    public boolean awaitUninterruptibly(long timeoutMillis) {
        fail();
        return false;
    }

    @Override// ����SocketChannel����
    public Channel channel() {
        return channel;
    }

    @Override// �Ƿ��Ѿ��������״̬��
    public boolean isDone() {
        return false;
    }

    @Override// �Ƿ��ڳɹ�״̬��
    public boolean isSuccess() {
        return false;
    }

    @Override// ����Ϊ���ɳ���״̬������true
    public boolean setUncancellable() {
        return true;
    }

    @Override// ����false״̬
    public boolean isCancellable() {
        return false;
    }

    @Override // ����false״̬
    public boolean isCancelled() {
        return false;
    }

    @Override// ����null�쳣
    public Throwable cause() {
        return null;
    }

    @Override// �׳�IllegalStateException�쳣
    public VoidChannelPromise sync() {
        fail();
        return this;
    }

    @Override// �׳�IllegalStateException�쳣
    public VoidChannelPromise syncUninterruptibly() {
        fail();
        return this;
    }
    @Override// �����쳣����
    public VoidChannelPromise setFailure(Throwable cause) {
        fireException(cause);
        return this;
    }

    @Override
    public VoidChannelPromise setSuccess() {
        return this;
    }

    @Override // �����쳣
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

    // �׳�IllegalStateException�쳣
    private static void fail() {
        throw new IllegalStateException("void future");
    }

    @Override
    public VoidChannelPromise setSuccess(Void result) {
        return this;
    }

    @Override // ����false
    public boolean trySuccess(Void result) {
        return false;
    }

    @Override
    public Void getNow() {
        return null;
    }

    // �����쳣���û���
    private void fireException(Throwable cause) {
        // Only fire the exception if the channel is open and registered
        // if not the pipeline is not setup and so it would hit the tail
        // of the pipeline.
        // See https://github.com/netty/netty/issues/1517
    	// ��fireExceptionΪtrueʱ,SocketChannel�Ѿ�ע�����
        if (fireException && channel.isRegistered()) {
            channel.pipeline().fireExceptionCaught(cause);
        }
    }
}
