package xyz.tofumc.mirage.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class MirageProtocol {
    public static ByteBuf encode(MessageType type, byte[] payload) {
        ByteBuf buf = Unpooled.buffer(8 + payload.length);
        buf.writeInt(type.getId());
        buf.writeInt(payload.length);
        buf.writeBytes(payload);
        return buf;
    }

    public static Message decode(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return null;
        }
        buf.markReaderIndex();
        int typeId = buf.readInt();
        int length = buf.readInt();

        if (buf.readableBytes() < length) {
            buf.resetReaderIndex();
            return null;
        }

        MessageType type = MessageType.fromId(typeId);
        byte[] payload = new byte[length];
        buf.readBytes(payload);

        return new Message(type, payload);
    }

    public static class Message {
        private final MessageType type;
        private final byte[] payload;

        public Message(MessageType type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        public MessageType getType() {
            return type;
        }

        public byte[] getPayload() {
            return payload;
        }
    }
}
