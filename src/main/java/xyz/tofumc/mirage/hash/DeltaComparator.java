package xyz.tofumc.mirage.hash;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeltaComparator {
    public static class Delta {
        private final List<String> filesToDownload;
        private final List<String> filesToDelete;

        public Delta(List<String> filesToDownload, List<String> filesToDelete) {
            this.filesToDownload = filesToDownload;
            this.filesToDelete = filesToDelete;
        }

        public List<String> getFilesToDownload() {
            return filesToDownload;
        }

        public List<String> getFilesToDelete() {
            return filesToDelete;
        }
    }

    public static Delta compare(Map<String, String> mainHashes, Map<String, String> mirrorHashes) {
        List<String> toDownload = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        for (Map.Entry<String, String> entry : mainHashes.entrySet()) {
            String filename = entry.getKey();
            String mainHash = entry.getValue();
            String mirrorHash = mirrorHashes.get(filename);

            if (mirrorHash == null || !mirrorHash.equals(mainHash)) {
                toDownload.add(filename);
            }
        }

        for (String filename : mirrorHashes.keySet()) {
            if (!mainHashes.containsKey(filename)) {
                toDelete.add(filename);
            }
        }

        return new Delta(toDownload, toDelete);
    }
}
