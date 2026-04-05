package xyz.tofumc.mirage.network.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.hash.FileTransferManager;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.network.protocol.MirageProtocol;
import xyz.tofumc.mirage.sync.MainServerTask;
import xyz.tofumc.mirage.util.DimensionPathUtil;
import xyz.tofumc.mirage.util.RegionFileUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class ServerHandler extends ChannelInboundHandlerAdapter {
    private final Mirage mirage;
    private final MirageSyncServer server;
    private final String authToken;
    private boolean authenticated = false;

    public ServerHandler(Mirage mirage, MirageSyncServer server, String authToken) {
        this.mirage = mirage;
        this.server = server;
        this.authToken = authToken;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) msg;
        try {
            MirageProtocol.Message message = MirageProtocol.decode(buf);
            if (message == null) {
                return;
            }

            if (!authenticated) {
                if (message.getType() == MessageType.HANDSHAKE) {
                    handleHandshake(ctx, message);
                } else {
                    ctx.close();
                }
                return;
            }

            switch (message.getType()) {
                case HASH_LIST_REQ -> handleHashListRequest(ctx, message);
                case FILE_SYNC_START -> handleFileSyncStart(ctx, message);
                case CHUNK_SYNC_REQ -> handleChunkSyncRequest(ctx, message);
                default -> Mirage.LOGGER.warn("Unknown message type: {}", message.getType());
            }
        } finally {
            buf.release();
        }
    }

    private void handleHandshake(ChannelHandlerContext ctx, MirageProtocol.Message message) {
        MessagePayloads.HandshakePayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.HandshakePayload.class);
        if (payload != null && authToken.equals(payload.token())) {
            authenticated = true;
            server.registerClient(ctx.channel(), payload.clientId());
            server.send(ctx.channel(), MessageType.HANDSHAKE, MessagePayloads.toBytes(new MessagePayloads.HandshakeResponsePayload(true)));
        } else {
            Mirage.LOGGER.warn("Authentication failed from {}", ctx.channel().remoteAddress());
            ctx.close();
        }
    }

    private void handleHashListRequest(ChannelHandlerContext ctx, MirageProtocol.Message message) {
        try {
            MessagePayloads.HashListRequestPayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.HashListRequestPayload.class);
            if (payload == null) {
                return;
            }
            MainServerTask task = mirage.getMainServerTask();
            if (task == null) {
                Mirage.LOGGER.warn("MainServerTask unavailable");
                return;
            }
            var world = mirage.getLevel(payload.dimension()).orElse(null);
            if (world == null) {
                Mirage.LOGGER.warn("Unknown requested dimension {}", payload.dimension());
                return;
            }
            MessagePayloads.HashListResponsePayload response = task.buildHashListPayload(world);
            server.send(ctx.channel(), MessageType.HASH_LIST_RESP, MessagePayloads.toBytes(response));
            Mirage.LOGGER.info("Sent hash list for {} to {}", payload.dimension(), ctx.channel().remoteAddress());
        } catch (Exception e) {
            Mirage.LOGGER.error("Failed to handle hash list request", e);
        }
    }

    private void handleFileSyncStart(ChannelHandlerContext ctx, MirageProtocol.Message message) {
        try {
            MessagePayloads.FileSyncRequestPayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.FileSyncRequestPayload.class);
            if (payload == null) {
                return;
            }
            var world = mirage.getLevel(payload.dimension()).orElse(null);
            if (world == null) {
                Mirage.LOGGER.warn("Unknown requested dimension {}", payload.dimension());
                return;
            }
            Path regionDir = DimensionPathUtil.getRegionDir(mirage.getServer(), world);
            for (String filename : payload.filesToDownload()) {
                Path file = regionDir.resolve(filename);
                if (!Files.exists(file)) {
                    Mirage.LOGGER.warn("Requested file does not exist: {}", file);
                    continue;
                }
                long size = Files.size(file);
                long offset = 0;
                while (offset < size) {
                    byte[] chunk = FileTransferManager.readChunk(file, offset);
                    boolean eof = offset + chunk.length >= size;
                    MessagePayloads.FileChunkPayload chunkPayload = new MessagePayloads.FileChunkPayload(
                        payload.dimension(),
                        filename,
                        offset,
                        eof,
                        Base64.getEncoder().encodeToString(chunk)
                    );
                    server.send(ctx.channel(), MessageType.FILE_CHUNK, MessagePayloads.toBytes(chunkPayload));
                    offset += chunk.length;
                }
            }
            server.send(ctx.channel(), MessageType.SYNC_DONE, MessagePayloads.toBytes(new MessagePayloads.SyncDonePayload(payload.dimension(), true)));
            Mirage.LOGGER.info("Finished sending files for {}", payload.dimension());
        } catch (Exception e) {
            Mirage.LOGGER.error("Failed to handle file sync start", e);
        }
    }

    private void handleChunkSyncRequest(ChannelHandlerContext ctx, MirageProtocol.Message message) {
        try {
            MessagePayloads.ChunkSyncRequestPayload payload = MessagePayloads.fromJson(message.getPayload(), MessagePayloads.ChunkSyncRequestPayload.class);
            if (payload == null) {
                return;
            }
            var world = mirage.getLevel(payload.dimension()).orElse(null);
            if (world == null) {
                Mirage.LOGGER.warn("Unknown requested dimension {}", payload.dimension());
                return;
            }
            Path regionDir = DimensionPathUtil.getRegionDir(mirage.getServer(), world);
            String mcaFileName = RegionFileUtil.getMcaFileName(payload.chunkX(), payload.chunkZ());
            Path mcaFile = regionDir.resolve(mcaFileName);

            byte[] chunkData = RegionFileUtil.readChunkData(mcaFile, payload.chunkX(), payload.chunkZ());
            if (chunkData == null) {
                Mirage.LOGGER.warn("Chunk ({}, {}) not found in {}", payload.chunkX(), payload.chunkZ(), mcaFileName);
                return;
            }

            String encoded = Base64.getEncoder().encodeToString(chunkData);
            MessagePayloads.ChunkSyncResponsePayload response = new MessagePayloads.ChunkSyncResponsePayload(
                payload.dimension(), payload.chunkX(), payload.chunkZ(), encoded
            );
            server.send(ctx.channel(), MessageType.CHUNK_SYNC_RESP, MessagePayloads.toBytes(response));
            Mirage.LOGGER.info("Sent chunk ({}, {}) for {} to {}", payload.chunkX(), payload.chunkZ(), payload.dimension(), ctx.channel().remoteAddress());
        } catch (Exception e) {
            Mirage.LOGGER.error("Failed to handle chunk sync request", e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        server.unregisterClient(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Mirage.LOGGER.error("Server handler error", cause);
        ctx.close();
    }
}
