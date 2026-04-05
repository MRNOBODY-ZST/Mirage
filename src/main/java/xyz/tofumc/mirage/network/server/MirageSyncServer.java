package xyz.tofumc.mirage.network.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.network.protocol.MirageFrameDecoder;
import xyz.tofumc.mirage.network.protocol.MirageProtocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MirageSyncServer {
    private final Mirage mirage;
    private final int port;
    private final String authToken;
    private final Map<Channel, String> connectedClients = new ConcurrentHashMap<>();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public MirageSyncServer(Mirage mirage, int port, String authToken) {
        this.mirage = mirage;
        this.port = port;
        this.authToken = authToken;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new MirageFrameDecoder());
                        ch.pipeline().addLast(new ServerHandler(mirage, MirageSyncServer.this, authToken));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            Mirage.LOGGER.info("Mirage sync server started on port {}", port);
        } catch (Exception e) {
            Mirage.LOGGER.error("Failed to start sync server", e);
            shutdown();
            throw e;
        }
    }

    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        connectedClients.clear();
        Mirage.LOGGER.info("Mirage sync server stopped");
    }

    public Mirage getMirage() {
        return mirage;
    }

    public void registerClient(Channel channel, String clientId) {
        connectedClients.put(channel, clientId);
        Mirage.LOGGER.info("Client connected: {}", clientId);
    }

    public void unregisterClient(Channel channel) {
        String clientId = connectedClients.remove(channel);
        if (clientId != null) {
            Mirage.LOGGER.info("Client disconnected: {}", clientId);
        }
    }

    public void broadcast(Object message) {
        for (Channel channel : connectedClients.keySet()) {
            if (channel.isActive()) {
                channel.writeAndFlush(message);
            }
        }
    }

    public void send(Channel channel, MessageType type, byte[] payload) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(encode(type, payload));
        }
    }

    public io.netty.buffer.ByteBuf encode(MessageType type, byte[] payload) {
        return MirageProtocol.encode(type, payload);
    }

    public Map<Channel, String> getConnectedClients() {
        return connectedClients;
    }
}
