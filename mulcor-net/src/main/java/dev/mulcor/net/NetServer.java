package dev.mulcor.net;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.function.IntSupplier;

/**
 * TCP front end on the best native transport available: epoll on Linux, kqueue on macOS/BSD, NIO elsewhere.
 * Buffers come from the pooled direct allocator. Event-loop threads are completely separate from the region
 * workers; the two sides communicate only through the lock-free ingress rings.
 */
public final class NetServer implements AutoCloseable {
    public enum Transport { EPOLL, KQUEUE, NIO }

    private final EventLoopGroup boss, workers;
    private final Channel channel;
    private final Transport transport;
    private final TokenBucket global;

    /**
     * @param entityForConnection assigns the entity a new connection controls (login is out of scope)
     */
    public NetServer(int port, int ioThreads, InputSink sink, IntSupplier entityForConnection,
            int perConnectionBurst, long perConnectionRate, long globalRate) throws InterruptedException {
        IoHandlerFactory factory;
        Class<? extends ServerChannel> channelClass;
        if (Epoll.isAvailable()) {
            transport = Transport.EPOLL;
            factory = EpollIoHandler.newFactory();
            channelClass = EpollServerSocketChannel.class;
        } else if (KQueue.isAvailable()) {
            transport = Transport.KQUEUE;
            factory = KQueueIoHandler.newFactory();
            channelClass = KQueueServerSocketChannel.class;
        } else {
            transport = Transport.NIO;
            factory = NioIoHandler.newFactory();
            channelClass = NioServerSocketChannel.class;
        }
        this.global = new TokenBucket((int) Math.min(TokenBucket.MAX_CAPACITY, Math.max(1, globalRate)), globalRate);
        boss = new MultiThreadIoEventLoopGroup(1, factory);
        workers = new MultiThreadIoEventLoopGroup(ioThreads, factory);
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, workers)
                .channel(channelClass)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var decoder = new IngressDecoder(entityForConnection.getAsInt(), sink, false);
                        ch.pipeline().addLast("mulcor-ingress", new IngressHandler(decoder,
                                new TokenBucket(perConnectionBurst, perConnectionRate), global));
                    }
                });
        channel = b.bind(port).sync().channel();
    }

    public Transport transport() { return transport; }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        boss.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        workers.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
    }
}
