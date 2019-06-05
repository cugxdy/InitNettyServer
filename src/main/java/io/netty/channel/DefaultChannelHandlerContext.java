/*
* Copyright 2014 The Netty Project
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

import io.netty.util.concurrent.EventExecutor;

final class DefaultChannelHandlerContext extends AbstractChannelHandlerContext {

	// 创建Handler的引用.
    private final ChannelHandler handler;

    DefaultChannelHandlerContext(
            DefaultChannelPipeline pipeline, EventExecutor executor, String name, ChannelHandler handler) {
        super(pipeline, executor, name, isInbound(handler), isOutbound(handler));// --> AbstractChannelHandlerContext类
        // 空指针抛出异常
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
    }

    @Override // 获取Handler引用
    public ChannelHandler handler() {
        return handler;
    }

    // 入栈处理器是ChannelInboundHandler的实现
    private static boolean isInbound(ChannelHandler handler) {
    	// 是否为ChannelInboundHandler类
        return handler instanceof ChannelInboundHandler;
    }

    // 出栈处理器是ChannelOutboundHandler的实现
    private static boolean isOutbound(ChannelHandler handler) {
    	// 是否为ChannelOutboundHandler类
        return handler instanceof ChannelOutboundHandler;
    }
}
