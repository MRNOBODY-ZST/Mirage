package xyz.tofumc.mirage.network.protocol;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public final class MessagePayloads {
    private static final Gson GSON = new Gson();

    private MessagePayloads() {
    }

    public static byte[] toBytes(Object payload) {
        return GSON.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static <T> T fromJson(byte[] payload, Class<T> type) {
        return GSON.fromJson(new String(payload, java.nio.charset.StandardCharsets.UTF_8), type);
    }

    public record HandshakePayload(String token, String clientId, String mode) {
    }

    public record HandshakeResponsePayload(boolean ok) {
    }

    public record HashListRequestPayload(String dimension) {
    }

    public record HashListResponsePayload(String dimension, Map<String, String> hashes) {
    }

    public record FileSyncRequestPayload(String dimension, List<String> filesToDownload, List<String> filesToDelete) {
    }

    public record FileChunkPayload(String dimension, String file, long offset, boolean eof, String data) {
    }

    public record SyncDonePayload(String dimension, boolean success) {
    }
}
