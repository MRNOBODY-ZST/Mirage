package xyz.tofumc.mirage.network.protocol;

public enum MessageType {
    HANDSHAKE(0),
    HASH_LIST_REQ(1),
    HASH_LIST_RESP(2),
    FILE_SYNC_START(3),
    FILE_CHUNK(4),
    SYNC_DONE(5),
    CHUNK_SYNC_REQ(6),
    CHUNK_SYNC_RESP(7);

    private final int id;

    MessageType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static MessageType fromId(int id) {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + id);
    }
}
