package io.test.proxy;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class MyServerInitlaber extends ChannelInitializer<SocketChannel> {

	@Override
	protected void initChannel(SocketChannel ch) throws Exception {
		// TODO Auto-generated method stub
		ChannelPipeline pipline = ch.pipeline();
		pipline.addLast("StringEncoder", new StringEncoder());
		pipline.addLast("StringDecoder",new StringDecoder());
		
		pipline.addLast("MyHandler",new MyServerHandler());
	}


}
