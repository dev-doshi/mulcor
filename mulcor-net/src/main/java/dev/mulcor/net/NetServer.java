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
     * A play-state-only server: every connection is bound to an existing entity and sends play packets straight
     * away, with no handshake or login. Used by benchmarks and floods.
     *
     * @param entityForConnection assigns the entity a new connection controls
     */
    public NetServer(int port, int ioThreads, InputSink sink, IntSupplier entityForConnection,
            int perConnectionBurst, long perConnectionRate, long globalRate) throws InterruptedException {
        this(port, ioThreads, new TokenBucket((int) Math.min(TokenBucket.MAX_CAPACITY, Math.max(1, globalRate)), globalRate),
                (ch, global) -> {
                    var decoder = new IngressDecoder(entityForConnection.getAsInt(), sink, false);
                    ch.pipeline().addLast("mulcor-ingress", new IngressHandler(decoder,
                            new TokenBucket(perConnectionBurst, perConnectionRate), global));
                });
    }

    /**
     * A server real Minecraft clients can join: each connection goes through the vanilla handshake, login and
     * configuration ({@link LoginHandler}), gets a player entity from the engine, and then streams chunks and
     * feeds its input into the region rings like any other connection.
     */
    public static NetServer vanilla(int port, int ioThreads, ServerContext server) throws InterruptedException {
        java.util.Objects.requireNonNull(Vanilla.REGISTRIES); // load Minestom's registries before the first client
        return new NetServer(port, ioThreads, server.globalBucket(),
                (ch, global) -> ch.pipeline().addLast("mulcor-login", new LoginHandler(server)));
    }

    private NetServer(int port, int ioThreads, TokenBucket global,
            java.util.function.BiConsumer<SocketChannel, TokenBucket> init) throws InterruptedException {
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
        this.global = global;
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
                        init.accept(ch, NetServer.this.global);
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
