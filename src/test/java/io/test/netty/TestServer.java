package io.test.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.Test;

public class TestServer {

	@Test
	public void test() {
		NioEventLoopGroup boss = new NioEventLoopGroup(1);
		NioEventLoopGroup worder = new NioEventLoopGroup(2);
		
		try {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(boss, worder).channel(NioServerSocketChannel.class)
			.childOption(ChannelOption.SO_BACKLOG, 1000)
			.childHandler(new MyServerHander());
			
			ChannelFuture future = bootstrap.bind(9999).sync();
			future.channel().closeFuture().sync();
			
		} catch (Exception e) {
			// TODO: handle exception
		}finally {
			boss.shutdownGracefully();
			worder.shutdownGracefully();
		}
		
	}
}
