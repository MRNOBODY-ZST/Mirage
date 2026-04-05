package xyz.tofumc.mirage.hash;

import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class RegionHasher {
    public static Map<String, String> hashRegionFiles(Path regionDir) throws IOException {
        Map<String, String> hashes = new HashMap<>();

        if (!Files.exists(regionDir)) {
            Mirage.LOGGER.warn("Region directory does not exist: {}", regionDir);
            return hashes;
        }

        try (Stream<Path> files = Files.list(regionDir)) {
            files.filter(path -> path.toString().endsWith(".mca"))
                 .forEach(path -> {
                     try {
                         String filename = path.getFileName().toString();
                         String hash = HashUtil.sha256(path);
                         hashes.put(filename, hash);
                     } catch (IOException e) {
                         Mirage.LOGGER.error("Failed to hash file: {}", path, e);
                     }
                 });
        }

        Mirage.LOGGER.info("Hashed {} region files from {}", hashes.size(), regionDir);
        return hashes;
    }
}
