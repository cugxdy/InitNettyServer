package io.test.client;

import org.junit.Test;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class MyClient {

	@Test
	public void test(){
		NioEventLoopGroup worker = new NioEventLoopGroup(1);
		
		try {
			Bootstrap bootstrap = new Bootstrap();
			bootstrap.group(worker).channel(NioSocketChannel.class)
			.handler(new ChannelInitializer<SocketChannel>() {

				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					// TODO Auto-generated method stub
					ChannelPipeline pipeline = ch.pipeline();
					pipeline.addLast(new StringDecoder());
					pipeline.addLast(new StringEncoder());
					pipeline.addLast(new MyClientHandler());
				}
			});
			
			ChannelFuture f = bootstrap.connect("127.0.0.1", 9999).sync();
			
			
			f.addListener(new ChannelFutureListener() {
				
				@Override
				public void operationComplete(ChannelFuture future) throws Exception {
					// TODO Auto-generated method stub
					if (future.isSuccess()) {
						System.out.println("连接建立成功");
					} else {
						future.channel().close();
					}
				}
			});
			
			f.channel().close().syncUninterruptibly();
		}catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		} finally {
			// TODO: handle finally clause
			worker.shutdownGracefully();
		}
	}
	
}
