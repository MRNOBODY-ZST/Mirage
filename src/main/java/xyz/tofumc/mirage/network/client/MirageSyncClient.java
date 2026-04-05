package xyz.tofumc.mirage.network.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.network.protocol.MirageFrameDecoder;
import xyz.tofumc.mirage.network.protocol.MirageProtocol;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MirageSyncClient {
    private final Mirage mirage;
    private final String host;
    private final int port;
    private final String authToken;
    private EventLoopGroup workerGroup;
    private Channel channel;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectDelay = new AtomicInteger(1);

    public MirageSyncClient(Mirage mirage, String host, int port, String authToken) {
        this.mirage = mirage;
        this.host = host;
        this.port = port;
        this.authToken = authToken;
    }

    public void connect() {
        workerGroup = new NioEventLoopGroup();
        doConnect();
    }

    private void doConnect() {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new MirageFrameDecoder());
                    ch.pipeline().addLast(new ClientHandler(mirage, MirageSyncClient.this));
                }
            });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                reconnectDelay.set(1);
                sendHandshake();
                Mirage.LOGGER.info("Connected to main server at {}:{}", host, port);
            } else {
                scheduleReconnect();
            }
        });
    }

    private void sendHandshake() {
        MessagePayloads.HandshakePayload payload = new MessagePayloads.HandshakePayload(authToken, "mirror-" + host + ':' + port, mirage.getMode());
        ByteBuf handshake = MirageProtocol.encode(MessageType.HANDSHAKE, MessagePayloads.toBytes(payload));
        channel.writeAndFlush(handshake);
    }

    private void scheduleReconnect() {
        if (workerGroup == null || workerGroup.isShuttingDown()) {
            return;
        }
        int delay = Math.min(reconnectDelay.getAndUpdate(d -> d * 2), 30);
        Mirage.LOGGER.warn("Connection failed, retrying in {} seconds...", delay);
        workerGroup.schedule(this::doConnect, delay, TimeUnit.SECONDS);
    }

    public Mirage getMirage() {
        return mirage;
    }

    public void onConnectionLost() {
        connected.set(false);
        scheduleReconnect();
    }

    public void onAuthenticated() {
        connected.set(true);
    }

    public void send(MessageType type, byte[] payload) {
        if (channel != null && channel.isActive()) {
            ByteBuf message = MirageProtocol.encode(type, payload);
            channel.writeAndFlush(message);
        }
    }

    public void disconnect() {
        connected.set(false);
        if (channel != null) {
            channel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        Mirage.LOGGER.info("Disconnected from main server");
    }

    public boolean isConnected() {
        return connected.get();
    }
}
