package io.test.proxy;

import org.junit.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class MainServer {
	
	@Test
	public void test() {
		
		NioEventLoopGroup boss = new NioEventLoopGroup(1);
		NioEventLoopGroup worker = new NioEventLoopGroup(1);
		
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(boss, worker).channel(NioServerSocketChannel.class)
			.option(ChannelOption.TCP_NODELAY, true)
			.childHandler(new MyServerInitlaber());
			
			
			ChannelFuture f = b.bind(9999).sync();
			f.channel().closeFuture().sync();
		} catch (Exception e) {
			// TODO: handle exception
		}finally {
			boss.shutdownGracefully();
			worker.shutdownGracefully();
		}
		
	}

}
