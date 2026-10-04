package com.shiyo.akiyggdrasil.config;

import org.tomlj.TomlTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of the remote skin re-signing service.
 *
 * <p>Players coming from an authentication source other than the one that owns
 * the signing key carry a {@code textures} property signed by a key their
 * peers' clients do not trust, so their skin silently falls back to the default
 * Steve/Alex. When a signer is configured, the server asks the skin site to
 * stamp such payloads with its own key; the payload (and therefore the texture
 * URLs) stays byte-identical, only the signature is replaced.
 */
public record SignerSettings(
    boolean enabled,
    String endpoint,
    String token,
    int timeoutMs,
    List<String> skipSources,
    boolean debugLog
) {

    public static final SignerSettings DISABLED =
        new SignerSettings(false, "", "", 3000, List.of(), false);

    /** Reads the {@code [signer]} table. Missing or invalid fields fall back to defaults. */
    public static SignerSettings parse(TomlTable root) {
        TomlTable table = root.getTable("signer");
        if (table == null) {
            return DISABLED;
        }

        boolean enabled = bool(table, "enabled", false);
        String endpoint = str(table, "endpoint");
        String token = str(table, "token");
        long timeout = longValue(table, "timeoutMs", 3000L);
        int timeoutMs = (int) Math.max(500L, Math.min(30_000L, timeout));
        boolean debugLog = bool(table, "debugLog", false);

        // skipSources 既接受 "A,B" 字符串，也接受 ["A", "B"] 数组
        List<String> skip = new ArrayList<>();
        Object rawSkip = table.get("skipSources");
        if (rawSkip instanceof String text) {
            for (String name : text.split(",")) {
                if (!name.isBlank()) {
                    skip.add(name.trim());
                }
            }
        } else if (table.isArray("skipSources")) {
            for (int i = 0; i < table.getArray("skipSources").size(); i++) {
                String name = table.getArray("skipSources").getString(i);
                if (name != null && !name.isBlank()) {
                    skip.add(name.trim());
                }
            }
        }

        if (enabled && (endpoint == null || endpoint.isBlank())) {
            enabled = false;
        }
        return new SignerSettings(
            enabled,
            endpoint == null ? "" : endpoint.trim(),
            token == null ? "" : token.trim(),
            timeoutMs,
            List.copyOf(skip),
            debugLog
        );
    }

    private static String str(TomlTable table, String key) {
        String value = table.getString(key);
        return value == null ? null : value.trim();
    }

    private static boolean bool(TomlTable table, String key, boolean fallback) {
        Object value = table.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private static long longValue(TomlTable table, String key, long fallback) {
        Object value = table.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    /** Serializes the {@code [signer]} table with bilingual inline comments. */
    public List<String> serialize() {
        List<String> lines = new ArrayList<>();
        lines.add("[signer]");
        lines.add("enabled = " + enabled
            + "  # 是否启用跨源皮肤重签 / re-sign foreign textures so every client can show them");
        lines.add("endpoint = " + q(endpoint)
            + "  # 皮肤站签名接口地址 / signing endpoint exposed by your skin site");
        lines.add("token = " + q(token)
            + "  # 接口鉴权令牌，与皮肤站插件里配置的一致 / bearer token shared with the skin site");
        lines.add("timeoutMs = " + timeoutMs
            + "  # 单次请求超时(毫秒)，超时则保留原属性不影响进服 / request timeout");
        lines.add("skipSources = " + q(String.join(",", skipSources))
            + "  # 这些认证源不重签，逗号分隔(填持有签名密钥的那个源) / sources left untouched");
        lines.add("debugLog = " + debugLog
            + "  # 把最终的纹理属性打到日志，仅供排查 / log the final textures property (troubleshooting)");
        return lines;
    }

    private static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
