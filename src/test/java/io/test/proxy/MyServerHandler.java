package io.test.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class MyServerHandler extends ChannelInboundHandlerAdapter {

	
	private SocketChannel channel;
	
	
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
    	 final Channel inboundChannel = ctx.channel();
    	
    	Bootstrap bootstrap = new Bootstrap();
    	bootstrap.group(ctx.channel().eventLoop()).channel(NioSocketChannel.class)
    	.handler(new ChannelInitializer<SocketChannel>() {

			@Override
			protected void initChannel(SocketChannel ch) throws Exception {
				// TODO Auto-generated method stub
				ChannelPipeline pipline = ch.pipeline();
				pipline.addLast("StringEncoder", new StringEncoder());
				pipline.addLast("StringDecoder",new StringDecoder());
				
				pipline.addLast("MyHandler",new MyClientHandler(inboundChannel));
			}
		});
    	
    	ChannelFuture future = bootstrap.connect("127.0.0.1", 9998);
    	
    	channel = (SocketChannel) future.channel();
    	
    	future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    // connection complete start to read first data
                    inboundChannel.read();
                } else {
                    // Close the connection if the connection attempt has failed.
                    inboundChannel.close();
                }
            }
        });
    }
    
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ctx.fireChannelRead(msg);
        System.out.println("Msg = " + msg);
        
        System.out.println("active = " + channel.isActive());
        if(channel.isActive()) {
        	System.out.println("qqqq");
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
	
}
