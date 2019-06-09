package io.test.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class MyClientHandler extends ChannelInboundHandlerAdapter {

	private Channel channel;
	
	public MyClientHandler(Channel inboundChannel) {
		// TODO Auto-generated constructor stub
		this.channel = inboundChannel;
	}
	
	
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("代理服务器启动");
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        
    	
    	channel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
			
			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				// TODO Auto-generated method stub
				if(future.isSuccess()) {
					future.channel().read();
				}else {
					future.channel().close();
				}
				
			}
		});

    }

}
