/*
 *   <copyright 
 *   notice="oco-source" 
 *   pids="5725-P60" 
 *   years="2015" 
 *   crc="1438874957" > 
 *   IBM Confidential 
 *    
 *   OCO Source Materials 
 *    
 *   5724-H72
 *    
 *   (C) Copyright IBM Corp. 2015
 *    
 *   The source code for the program is not published 
 *   or otherwise divested of its trade secrets, 
 *   irrespective of what has been deposited with the 
 *   U.S. Copyright Office. 
 *   </copyright> 
 */

package com.ibm.mqlight.api.impl.network;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.GenericFutureListener;

import com.ibm.mqlight.api.network.Network;
import com.ibm.mqlight.api.network.NetworkChannel;
import com.ibm.mqlight.api.network.NetworkConnectFuture;
import com.ibm.mqlight.api.network.NetworkCloseFuture;
import com.ibm.mqlight.api.network.NetworkListener;
import com.ibm.mqlight.api.network.NetworkWriteFuture;

public class NettyNetwork implements Network {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static Bootstrap bootstrap;
    
    static class NettyInboundHandler extends ChannelInboundHandlerAdapter implements NetworkChannel {
        
        private final Logger logger = LoggerFactory.getLogger(getClass());
        
        private final SocketChannel channel;
        private NetworkListener listener = null;
        private AtomicBoolean closed = new AtomicBoolean(false);
        
        protected NettyInboundHandler(SocketChannel channel) {
            this.channel = channel;
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            logger.debug("channel read");
            if (listener != null) listener.onRead(this, ((ByteBuf)msg).nioBuffer());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("exception caught: {}", cause);
            ctx.close();
            Exception exception;
            if (cause instanceof Exception) {
                exception = (Exception)cause;
            } else {
                exception = new Exception(cause);   // TODO: wrap in a better exception...
            }
            if (listener != null) {
                listener.onError(this, exception);
            }
            decrementUseCount();
        }
        
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx)
                throws Exception {
            logger.debug("channelWritabilityChanged");
            WriteRequest request = null;
            synchronized(pendingWrites) {
                if (ctx.channel().isWritable() && !pendingWrites.isEmpty()) {
                    request = pendingWrites.removeFirst();
                }
            }
            if (request != null) {
                doWrite(request);
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            logger.debug("channelInactive");
            boolean alreadyClosed = closed.getAndSet(true);
            if (!alreadyClosed) {
                if (listener != null) {
                    listener.onClose(this);
                }
                decrementUseCount();
            }
        }
        
        protected void setListener(NetworkListener listener) {
            logger.debug("setting listener");
            this.listener = listener;
        }

        @Override
        public void close(final NetworkCloseFuture nwfuture) {
            logger.debug("close");
            boolean alreadyClosed = closed.getAndSet(true);
            if (!alreadyClosed) {
                final ChannelFuture f = channel.disconnect();
                if (nwfuture != null) {                                                             // TODO: in general, should be able to handle null futures.
                    f.addListener(new GenericFutureListener<ChannelFuture>() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            nwfuture.setComplete();
                            decrementUseCount();
                        }
                    });
                }
            }
        }

        private class WriteRequest {
            protected final ByteBuf buffer;
            protected final NetworkWriteFuture future;
            protected WriteRequest(ByteBuf buffer, NetworkWriteFuture future) {
                this.buffer = buffer;
                this.future = future;
            }
        }
        
        LinkedList<WriteRequest> pendingWrites = new LinkedList<>();
        
        @Override
        public void write(ByteBuffer buffer, NetworkWriteFuture future) {
            ByteBuf byteBuf = Unpooled.wrappedBuffer(buffer);
            doWrite(new WriteRequest(byteBuf, future));
        }
        
        private void doWrite(final WriteRequest writeRequest) {
            logger.debug("doWrite");
            if (channel.isWritable()) {
                logger.debug(" -- doing write");
                final ChannelFuture f = channel.pipeline().writeAndFlush(writeRequest.buffer);
                f.addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        boolean havePendingWrites = false;
                        WriteRequest nextRequest = null;
                        synchronized(pendingWrites) {
                            havePendingWrites = !pendingWrites.isEmpty();
                            if (future.isSuccess() && future.channel().isWritable() && havePendingWrites) {
                                nextRequest = pendingWrites.removeFirst();
                            }
                        }
                        logger.debug("doWrite (complete)");
                        writeRequest.future.setComplete(!havePendingWrites);
                        if (nextRequest != null) {
                            doWrite(nextRequest);
                        }
                    }
                });
            } else {
                synchronized(pendingWrites) {
                    pendingWrites.addLast(writeRequest);
                    logger.debug(" -- deferring write");
                }
            }
        }

        private Object context;
        
        @Override
        public synchronized void setContext(Object context) {
            this.context = context;
        }

        @Override
        public synchronized Object getContext() {
            return context;
        }
    }

    protected class ConnectListener implements GenericFutureListener<ChannelFuture> {
        private final NetworkConnectFuture ncFuture;
        private final NetworkListener listener;
        protected ConnectListener(ChannelFuture cFuture, NetworkConnectFuture ncFuture, NetworkListener listener) {
            this.ncFuture = ncFuture;
            this.listener = listener;
            
        }
        @Override
        public void operationComplete(ChannelFuture cFuture) throws Exception {
            if (cFuture.isSuccess()) {
                NettyInboundHandler handler = (NettyInboundHandler)cFuture.channel().pipeline().last();
                handler.setListener(listener);
                ncFuture.setSuccess(handler);
            } else {
                Exception cause;
                if (cFuture.cause() instanceof Exception) {
                    cause = (Exception)cFuture.cause();
                } else {
                    cause = new Exception(cFuture.cause()); // TODO: would be better to use a more specific exception type.
                }
                ncFuture.setFailure(cause);
                decrementUseCount();
            }
        }
        
    }
    
    @Override
    public void connect(String host, int port, NetworkListener listener, NetworkConnectFuture future) {
        logger.debug("connect {} {}", host, port);
        incrementUseCount();
        final ChannelFuture f = bootstrap.connect(host, port);
        f.addListener(new ConnectListener(f, future, listener));
        
    }
    
    private static int useCount = 0;
    
    private static synchronized void incrementUseCount() {
        if (useCount == 0) {
            ++useCount;
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            bootstrap = new Bootstrap();
            bootstrap.group(workerGroup);
            bootstrap.channel(NioSocketChannel.class);
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new NettyInboundHandler(ch));
                }
            });
        }
    }
    
    private static synchronized void decrementUseCount() {
        --useCount;
        if (useCount <= 0) {
            useCount = 0;
            if (bootstrap != null) {
                bootstrap.group().shutdownGracefully(0, 500, TimeUnit.MILLISECONDS);
            }
            bootstrap = null;
        }
    }
}
