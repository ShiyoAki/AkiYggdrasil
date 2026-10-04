package com.shiyo.akiyggdrasil.config;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot migration from the pre-1.0 {@code akiyggdrasil.ini} layout (INI with
 * one {@code [Section]} per auth source) to the current TOML layout.
 *
 * <p>Without this the first start after upgrading would not find any TOML file,
 * silently write the built-in default and drop every source the owner had
 * configured - i.e. a server that meant to accept skin-site players would end
 * up accepting Mojang only (or, worse, a default that included a public skin
 * site). The old file is kept as {@code *.ini.bak} so nothing is lost.
 */
public final class LegacyIniConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LegacyIniConfig() {
    }

    public static Path legacyPath(Path tomlPath) {
        String name = tomlPath.getFileName().toString();
        String base = name.endsWith(".toml") ? name.substring(0, name.length() - 5) : name;
        return tomlPath.resolveSibling(base + ".ini");
    }

    /**
     * Parses the legacy INI file into an ordered {@code section -> key/value} map.
     * Returns an empty map when the file cannot be read.
     */
    public static Map<String, Map<String, String>> parse(Path ini) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(ini, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[AkiYggdrasil] Cannot read legacy config {}", ini, e);
            return sections;
        }

        Map<String, String> current = null;
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                if (end > 1) {
                    String name = line.substring(1, end).trim();
                    current = new LinkedHashMap<>();
                    sections.put(name, current);
                } else {
                    current = null;
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && current != null) {
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    current.put(key, value);
                }
            }
        }
        return sections;
    }

    /**
     * Converts a legacy INI file into the current TOML file. The INI file is
     * renamed to {@code .ini.bak} so the migration only ever happens once.
     * Returns {@code null} when there is nothing usable to migrate.
     */
    public static AkiConfig migrate(Path ini, Path toml) {
        Map<String, Map<String, String>> sections = parse(ini);
        List<AuthSource> sources = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : sections.entrySet()) {
            AuthSource source = AuthSource.parse(entry.getKey(), entry.getValue());
            if (source == null) {
                LOGGER.warn("[AkiYggdrasil] Skipping legacy source [{}]: incomplete section", entry.getKey());
                continue;
            }
            sources.add(source);
        }
        if (sources.isEmpty()) {
            return null;
        }

        AkiConfig config = new AkiConfig(sources, SignerSettings.DISABLED);
        config.save(toml);
        Path backup = ini.resolveSibling(ini.getFileName() + ".bak");
        try {
            Files.move(ini, backup);
            LOGGER.info("[AkiYggdrasil] Migrated legacy config {} -> {} (old file kept as {})",
                ini.getFileName(), toml.getFileName(), backup.getFileName());
        } catch (IOException e) {
            LOGGER.warn("[AkiYggdrasil] Migrated to {} but could not rename {}", toml.getFileName(), ini, e);
        }
        LOGGER.info("[AkiYggdrasil] Migrated {} auth source(s): {}", sources.size(), config.sources());
        return config;
    }

    /** Removes the trailing {@code  # comment} part; URLs are unaffected. */
    private static String stripComment(String line) {
        int idx = line.indexOf(" #");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
