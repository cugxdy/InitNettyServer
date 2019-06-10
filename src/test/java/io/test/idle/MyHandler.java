package io.test.idle;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

public class MyHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("服务器启动");
    }
    
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println(msg);
    }
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    	System.out.println("空闲事件触发");
    	IdleStateEvent e = null;
    	if (evt instanceof  IdleStateEvent) {
    		e = (IdleStateEvent) evt;
    		if (e.state() == IdleState.READER_IDLE) {
    			System.out.println("读空闲事件触发");
    			ctx.close();
    		} else if (e.state() == IdleState.WRITER_IDLE) {
    			System.out.println("写空闲事件触发");
    			ctx.writeAndFlush("hello world");
    		}
    	}
    }
    
}
