package io.test.idle;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

public class MyHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("����������");
    }
    
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println(msg);
    }
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    	System.out.println("�����¼�����");
    	IdleStateEvent e = null;
    	if (evt instanceof  IdleStateEvent) {
    		e = (IdleStateEvent) evt;
    		if (e.state() == IdleState.READER_IDLE) {
    			System.out.println("�������¼�����");
    			ctx.close();
    		} else if (e.state() == IdleState.WRITER_IDLE) {
    			System.out.println("д�����¼�����");
    			ctx.writeAndFlush("hello world");
    		}
    	}
    }
    
}
