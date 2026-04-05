package xyz.tofumc.mirage.network.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.network.protocol.MirageProtocol;
import xyz.tofumc.mirage.sync.MirrorApplyTask;

public class ClientHandler extends ChannelInboundHandlerAdapter {
    private final Mirage mirage;
    private final MirageSyncClient client;

    public ClientHandler(Mirage mirage, MirageSyncClient client) {
        this.mirage = mirage;
        this.client = client;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) msg;
        try {
            MirageProtocol.Message message = MirageProtocol.decode(buf);
            if (message == null) {
                return;
            }

            switch (message.getType()) {
                case HANDSHAKE -> handleHandshake(message);
                case HASH_LIST_RESP -> handleHashListResponse(message);
                case FILE_CHUNK -> handleFileChunk(message);
                case SYNC_DONE -> handleSyncDone(message);
                default -> Mirage.LOGGER.warn("Unknown message type: {}", message.getType());
            }
        } finally {
            buf.release();
        }
    }

    private void handleHandshake(MirageProtocol.Message message) {
        MessagePayloads.HandshakeResponsePayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.HandshakeResponsePayload.class);
        if (payload != null && payload.ok()) {
            client.onAuthenticated();
            Mirage.LOGGER.info("Authentication successful");
        }
    }

    private void handleHashListResponse(MirageProtocol.Message message) {
        MirrorApplyTask task = mirage.getMirrorApplyTask();
        if (task == null) {
            Mirage.LOGGER.warn("MirrorApplyTask unavailable");
            return;
        }
        MessagePayloads.HashListResponsePayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.HashListResponsePayload.class);
        if (payload != null) {
            task.applyHashList(payload);
        }
    }

    private void handleFileChunk(MirageProtocol.Message message) {
        MirrorApplyTask task = mirage.getMirrorApplyTask();
        if (task == null) {
            return;
        }
        MessagePayloads.FileChunkPayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.FileChunkPayload.class);
        if (payload != null) {
            task.handleFileChunk(payload);
        }
    }

    private void handleSyncDone(MirageProtocol.Message message) {
        MirrorApplyTask task = mirage.getMirrorApplyTask();
        if (task == null) {
            return;
        }
        MessagePayloads.SyncDonePayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.SyncDonePayload.class);
        if (payload != null) {
            task.handleSyncDone(payload);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Mirage.LOGGER.warn("Connection lost to main server");
        client.onConnectionLost();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Mirage.LOGGER.error("Client handler error", cause);
        ctx.close();
    }
}
