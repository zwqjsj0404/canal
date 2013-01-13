package com.alibaba.otter.canal.server.netty.handler;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.protocol.E3;
import com.alibaba.otter.canal.protocol.E3.E3Packet;
import com.alibaba.otter.canal.protocol.E3.Handshake;
import com.alibaba.otter.canal.server.netty.NettyUtils;

/**
 * handshake交互
 * 
 * @author jianghang 2012-10-24 上午11:39:54
 * @version 4.1.2
 */
public class HandshakeInitializationHandler extends SimpleChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(HandshakeInitializationHandler.class);

    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        byte[] body = E3Packet.newBuilder().setType(E3.PacketType.HANDSHAKE).setBody(
                                                                                     Handshake.newBuilder().build().toByteString()).build().toByteArray();
        NettyUtils.write(ctx.getChannel(), body, null);
        logger.info("send handshake initialization packet to : {}", ctx.getChannel());
    }
}