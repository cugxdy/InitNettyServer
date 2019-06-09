package io.test.netty;
 
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class MyHandler extends SimpleChannelInboundHandler<String> {

	
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("��ʵ����������");
    }
	
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
		// TODO Auto-generated method stub
		System.out.println("��ʵ������");
		ctx.writeAndFlush(msg);
	}
}
