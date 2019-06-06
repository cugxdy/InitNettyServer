package io.test.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class MyServerHander extends  ChannelInitializer<Channel> {

	@Override
	protected void initChannel(Channel ch) throws Exception {
		// TODO Auto-generated method stub
		ChannelPipeline pip = ch.pipeline();
		pip.addLast("StringEncoder",new StringEncoder());
		pip.addLast("StringDecoer",new StringDecoder());
		pip.addLast("MyHandler",new MyHandler());
	}

}
