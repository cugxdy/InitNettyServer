package io.test.nio;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class NioTest {
//	private static  final Selector selector;
//	private static  final SocketChannel server;
//	
//	static {
//		selector = SelectorProvider.provider().openSelector();
//		server = SocketChannel.open();
//	}
//	
	@Test
	public void test() throws IOException {
		
		// ServerSocketChannel serverSocket = ServerSocketChannel.open();
		
		System.out.println(TimeUnit.SECONDS.toNanos(1));
	}
}
