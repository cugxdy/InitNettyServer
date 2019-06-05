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

/**
 * {@link ChannelHandler} which adds callbacks for state changes. This allows the user
 * to hook in to state changes easily.
 */
public interface ChannelInboundHandler extends ChannelHandler {

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was registered with its {@link EventLoop}
     */
	// ��channelע�ᵽ�̳߳ص�ʱ��ᱻ������
    void channelRegistered(ChannelHandlerContext ctx) throws Exception;

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was unregistered from its {@link EventLoop}
     */
    // ��channel�رյ�ʱ�򴥷���
    void channelUnregistered(ChannelHandlerContext ctx) throws Exception;

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} is now active
     */
    // registered���֮����channel����active״̬���״�ע��״̬��
    void channelActive(ChannelHandlerContext ctx) throws Exception;

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was registered is now inactive and reached its
     * end of lifetime.
     */
    // unregistered֮ǰִ�У���Ҫ��AbstractChannel��deregister������
    void channelInactive(ChannelHandlerContext ctx) throws Exception;

    /**
     * Invoked when the current {@link Channel} has read a message from the peer.
     */
    // �䱻channel�ڻ�ȡ�����ݵĽ׶ν��е��ã�����������handler��channelRead������
    void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception;

    /**
     * Invoked when the last message read by the current read operation has been consumed by
     * {@link #channelRead(ChannelHandlerContext, Object)}.  If {@link ChannelOption#AUTO_READ} is off, no further
     * attempt to read an inbound data from the current {@link Channel} will be made until
     * {@link ChannelHandlerContext#read()} is called.
     */
    // fireChannelReadComplete��������channel���й�����read������ɻ���е��á�
    void channelReadComplete(ChannelHandlerContext ctx) throws Exception;

    /**
     * Gets called if an user event was triggered.
     */
    // ���Ҳ����pipeline�ṩ�ķ�����Ϊ���fireUserEventTriggered��������Ǵ���һ���¼��ˣ���IdleStateHandlerΪ����
    // ��һ����Ϊ��������¼��������̳߳�ִ�У��жϿ��оͻᴥ���÷���������������handler��
    void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception;

    /**
     * Gets called once the writable state of a {@link Channel} changed. You can check the state with
     * {@link Channel#isWritable()}.
     */
    // ������Ҳ��pipeline,���Ǻ���û����ô�õ���channel��û�е������������һ��Ҳû��ô�ø÷�����
    void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception;

    /**
     * Gets called if a {@link Throwable} was thrown.
     */
    @Override
    @SuppressWarnings("deprecated")
    // ������ͬ����pipeline����channel�Ķ�ȡ�����׳��쳣ʱ��������Ȼ��ֻ��һ���ط���
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;
}
