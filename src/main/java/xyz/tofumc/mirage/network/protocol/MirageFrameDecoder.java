package xyz.tofumc.mirage.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class MirageFrameDecoder extends ByteToMessageDecoder {
    private static final int HEADER_SIZE = 8;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < HEADER_SIZE) {
            return;
        }

        in.markReaderIndex();
        in.readInt();
        int length = in.readInt();

        if (length < 0) {
            ctx.close();
            return;
        }

        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        in.resetReaderIndex();
        out.add(in.readRetainedSlice(HEADER_SIZE + length));
    }
}
