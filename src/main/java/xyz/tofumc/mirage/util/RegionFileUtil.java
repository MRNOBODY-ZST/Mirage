package xyz.tofumc.mirage.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public class RegionFileUtil {
    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SIZE = SECTOR_SIZE * 2; // 8KB: offset table + timestamp table

    /**
     * Compute the .mca filename for a given chunk coordinate.
     */
    public static String getMcaFileName(int chunkX, int chunkZ) {
        return "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
    }

    /**
     * Read raw chunk data (length prefix + compression type + compressed NBT) from an .mca file.
     * Returns null if the chunk does not exist.
     */
    public static byte[] readChunkData(Path mcaFile, int chunkX, int chunkZ) throws IOException {
        if (!Files.exists(mcaFile)) {
            return null;
        }

        int index = (chunkX & 31) + (chunkZ & 31) * 32;

        try (RandomAccessFile raf = new RandomAccessFile(mcaFile.toFile(), "r")) {
            if (raf.length() < HEADER_SIZE) {
                return null;
            }

            // Read offset entry from header
            raf.seek((long) index * 4);
            int offsetEntry = raf.readInt();
            if (offsetEntry == 0) {
                return null; // Chunk not present
            }

            int sectorOffset = (offsetEntry >> 8) & 0xFFFFFF;
            int sectorCount = offsetEntry & 0xFF;

            if (sectorOffset < 2 || sectorCount == 0) {
                return null;
            }

            long dataStart = (long) sectorOffset * SECTOR_SIZE;
            if (dataStart >= raf.length()) {
                return null;
            }

            // Read the chunk: first 4 bytes = data length, then the actual data
            raf.seek(dataStart);
            int dataLength = raf.readInt();
            if (dataLength <= 0 || dataLength > sectorCount * SECTOR_SIZE) {
                return null;
            }

            // Read length prefix (4 bytes) + actual data
            byte[] data = new byte[4 + dataLength];
            // Write the length prefix back
            data[0] = (byte) (dataLength >> 24);
            data[1] = (byte) (dataLength >> 16);
            data[2] = (byte) (dataLength >> 8);
            data[3] = (byte) dataLength;
            raf.readFully(data, 4, dataLength);

            return data;
        }
    }

    /**
     * Write raw chunk data into an .mca file using append strategy.
     * The data should include the 4-byte length prefix + compression type + compressed NBT.
     */
    public static void writeChunkData(Path mcaFile, int chunkX, int chunkZ, byte[] data) throws IOException {
        int index = (chunkX & 31) + (chunkZ & 31) * 32;

        boolean isNew = !Files.exists(mcaFile);

        try (RandomAccessFile raf = new RandomAccessFile(mcaFile.toFile(), "rw")) {
            if (isNew || raf.length() < HEADER_SIZE) {
                // Initialize empty header
                raf.setLength(HEADER_SIZE);
            }

            // Calculate how many sectors the data needs
            int sectorsNeeded = (data.length + SECTOR_SIZE - 1) / SECTOR_SIZE;

            // Append strategy: write data at the end of the file
            long fileLength = raf.length();
            // Align to sector boundary
            long appendOffset = ((fileLength + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE;
            if (appendOffset < HEADER_SIZE) {
                appendOffset = HEADER_SIZE;
            }

            // Pad the file to the append offset if needed
            if (raf.length() < appendOffset) {
                raf.setLength(appendOffset);
            }

            // Write chunk data
            raf.seek(appendOffset);
            raf.write(data);

            // Pad to sector boundary
            long endPos = appendOffset + data.length;
            long paddedEnd = ((endPos + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE;
            if (paddedEnd > endPos) {
                raf.setLength(paddedEnd);
            }

            // Update offset table in header
            int sectorNumber = (int) (appendOffset / SECTOR_SIZE);
            int offsetEntry = (sectorNumber << 8) | (sectorsNeeded & 0xFF);
            raf.seek((long) index * 4);
            raf.writeInt(offsetEntry);

            // Update timestamp table
            int timestamp = (int) (System.currentTimeMillis() / 1000);
            raf.seek(SECTOR_SIZE + (long) index * 4);
            raf.writeInt(timestamp);
        }
    }
}
