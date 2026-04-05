package xyz.tofumc.mirage.hash;

import xyz.tofumc.Mirage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FileTransferManager {
    private static final int CHUNK_SIZE = 512 * 1024; // 512KB

    public static byte[] readChunk(Path file, long offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             FileChannel channel = raf.getChannel()) {

            long remaining = channel.size() - offset;
            int toRead = (int) Math.min(CHUNK_SIZE, remaining);

            ByteBuffer buffer = ByteBuffer.allocate(toRead);
            channel.position(offset);
            channel.read(buffer);

            return buffer.array();
        }
    }

    public static void writeChunk(Path tempFile, byte[] data, long offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {

            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            channel.write(buffer);
        }
    }

    public static void atomicMove(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Mirage.LOGGER.info("Atomically moved {} to {}", source.getFileName(), target.getFileName());
    }
}
