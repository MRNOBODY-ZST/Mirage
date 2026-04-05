package xyz.tofumc.mirage.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path configPath;
    private MirageConfig config;

    public ConfigManager(Path configDir) {
        this.configPath = configDir.resolve("mirage.json");
    }

    public MirageConfig load() throws IOException {
        if (!Files.exists(configPath)) {
            config = new MirageConfig();
            save();
            return config;
        }

        String json = Files.readString(configPath);
        config = GSON.fromJson(json, MirageConfig.class);
        return config;
    }

    public void save() throws IOException {
        Files.createDirectories(configPath.getParent());
        String json = GSON.toJson(config);
        Files.writeString(configPath, json);
    }

    public MirageConfig getConfig() {
        return config;
    }
}
