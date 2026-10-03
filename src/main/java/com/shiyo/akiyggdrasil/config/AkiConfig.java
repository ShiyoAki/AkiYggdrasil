package com.shiyo.akiyggdrasil.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The list of Yggdrasil authentication sources, read from
 * {@code config/akiyggdrasil.toml}. When no config file exists the built-in
 * default is written to disk so it can be edited by the server owner.
 *
 * <p>The config file is hot-reloaded: a background daemon thread watches the
 * file for modifications and swaps the singleton in place, notifying every
 * registered {@link #addReloadListener(Consumer) reload listener} (the auth
 * service uses that to rebuild its environments live). A file that is only
 * half-written keeps the previous configuration instead of breaking the server.
 */
public final class AkiConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_FILE_NAME = "akiyggdrasil.toml";
    private static final long WATCH_INTERVAL_MS = 3000;

    public static final AkiConfig DEFAULT = new AkiConfig(List.of(
        AuthSource.api("LittleSkin", "https://littleskin.cn/api/yggdrasil", 0),
        AuthSource.official("MojangOfficial", 1)
    ));

    private static volatile AkiConfig cached;
    private static volatile boolean watching;
    private static final List<Consumer<AkiConfig>> reloadListeners = new CopyOnWriteArrayList<>();

    private final List<AuthSource> sources;

    public AkiConfig(List<AuthSource> sources) {
        List<AuthSource> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.naturalOrder());
        this.sources = List.copyOf(sorted);
    }

    public List<AuthSource> sources() {
        return sources;
    }

    public static AkiConfig get() {
        AkiConfig local = cached;
        if (local == null) {
            synchronized (AkiConfig.class) {
                local = cached;
                if (local == null) {
                    local = load(defaultPath());
                    cached = local;
                }
            }
        }
        return local;
    }

    /** Resets the process-wide singleton (used by tests). */
    public static void reset() {
        synchronized (AkiConfig.class) {
            cached = null;
        }
    }

    public static Path defaultPath() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE_NAME);
    }

    public static AkiConfig load(Path path) {
        if (!Files.exists(path)) {
            DEFAULT.save(path);
            LOGGER.info("[AkiYggdrasil] Wrote default config to {}", path);
            return DEFAULT;
        }

        AkiConfig config;
        try {
            config = parse(path);
            LOGGER.info("[AkiYggdrasil] Loaded {} auth source(s) from {}", config.sources.size(), path);
        } catch (Exception e) {
            LOGGER.warn("[AkiYggdrasil] Cannot read config {}, using defaults", path, e);
            config = DEFAULT;
        }
        return config;
    }

    public static AkiConfig parse(Path path) throws IOException {
        TomlParseResult result = Toml.parse(path);
        if (result.hasErrors()) {
            throw new IllegalArgumentException("Invalid TOML in " + path + ": " + result.errors());
        }

        List<AuthSource> sources = new ArrayList<>();
        TomlArray array = result.getArray("sources");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                TomlTable table = array.getTable(i);
                String name = table.getString("name");
                if (name == null || name.isBlank()) {
                    LOGGER.warn("[AkiYggdrasil] Skipping sources[{}]: missing 'name'", i);
                    continue;
                }
                if (sources.stream().anyMatch(s -> s.name().equals(name))) {
                    throw new IllegalArgumentException("Duplicate auth source name: " + name);
                }
                Map<String, String> kv = new LinkedHashMap<>();
                for (String key : table.keySet()) {
                    Object value = table.get(key);
                    if (value != null) {
                        kv.put(key, String.valueOf(value));
                    }
                }
                AuthSource source = AuthSource.parse(name, kv);
                if (source == null) {
                    LOGGER.warn("[AkiYggdrasil] Skipping invalid auth source [{}]", name);
                    continue;
                }
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("No valid auth sources in " + path);
        }
        return new AkiConfig(sources);
    }

    public void save(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write("# ============================================================");
                writer.newLine();
                writer.write("#  AkiYggdrasil authentication sources  /  认证源配置");
                writer.newLine();
                writer.write("#  每个 [[sources]] 表都是一个认证源。玩家登录时，服务器按 ordinal 从小");
                writer.newLine();
                writer.write("#  到大依次询问，直到某个源确认了玩家的会话为止。");
                writer.newLine();
                writer.write("#  修改保存后约 3 秒自动热重载，无需重启服务器。");
                writer.newLine();
                writer.write("#  (Changes are hot-reloaded within a few seconds; no restart needed.)");
                writer.newLine();
                writer.write("#  删除本文件可恢复默认配置。");
                writer.newLine();
                writer.write("# ============================================================");
                writer.newLine();
                writer.newLine();
                for (AuthSource source : sources) {
                    writer.write("[[sources]]");
                    writer.newLine();
                    for (String line : source.serialize()) {
                        writer.write(line);
                        writer.newLine();
                    }
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[AkiYggdrasil] Cannot save config to {}", path, e);
        }
    }

    // ---------------------------------------------------------------- hot reload

    /** Registers a listener invoked with the new config after every successful reload. */
    public static void addReloadListener(Consumer<AkiConfig> listener) {
        reloadListeners.add(listener);
    }

    /** Starts the config file watcher. Idempotent; safe to call from server start. */
    public static synchronized void startWatching() {
        if (watching) {
            return;
        }
        watching = true;
        Thread thread = new Thread(AkiConfig::watchLoop, "AkiYggdrasil-ConfigWatcher");
        thread.setDaemon(true);
        thread.start();
    }

    private static void watchLoop() {
        Path path = defaultPath();
        long lastModified = lastModifiedOf(path);
        while (watching) {
            try {
                Thread.sleep(WATCH_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            long modified = lastModifiedOf(path);
            if (modified != lastModified) {
                if (reloadFrom(path)) {
                    lastModified = modified;
                } else {
                    lastModified = -1; // retry next tick (file may be mid-edit)
                }
            }
        }
    }

    private static long lastModifiedOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Parses {@code path} and, on success, swaps the singleton and notifies
     * listeners. Returns {@code false} (keeping the previous config) when the
     * file cannot be parsed right now.
     */
    static boolean reloadFrom(Path path) {
        try {
            AkiConfig next = parse(path);
            synchronized (AkiConfig.class) {
                cached = next;
            }
            LOGGER.info("[AkiYggdrasil] Config hot-reloaded from {}", path);
            for (Consumer<AkiConfig> listener : reloadListeners) {
                try {
                    listener.accept(next);
                } catch (Exception e) {
                    LOGGER.warn("[AkiYggdrasil] Reload listener failed", e);
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("[AkiYggdrasil] Hot reload failed, keeping previous config: {}", e.toString());
            return false;
        }
    }
}
