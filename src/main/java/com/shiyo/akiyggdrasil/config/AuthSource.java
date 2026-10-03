package com.shiyo.akiyggdrasil.config;

import com.mojang.authlib.Environment;
import com.mojang.authlib.yggdrasil.YggdrasilEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One configured Yggdrasil authentication source.
 *
 * <p>An {@link AuthSourceType#API} source is described by its API root
 * (for example {@code https://littleskin.cn/api/yggdrasil}); the session
 * and services hosts are derived following the authlib-injector URL layout:
 * {@code <apiRoot>/sessionserver} and {@code <apiRoot>/minecraftservices}.
 *
 * <p>An {@link AuthSourceType#OFFICIAL} source uses explicit session/services
 * hosts and falls back to Mojang's production endpoints when they are omitted.
 */
public final class AuthSource implements Comparable<AuthSource> {

    public static final String KEY_TYPE = "type";
    public static final String KEY_API_ROOT = "apiRoot";
    public static final String KEY_SESSION_HOST = "sessionHost";
    public static final String KEY_SERVICES_HOST = "servicesHost";
    public static final String KEY_ORDINAL = "ordinal";

    private final String name;
    private final AuthSourceType type;
    private final String apiRoot;
    private final String sessionHost;
    private final String servicesHost;
    private final int ordinal;

    private AuthSource(String name, AuthSourceType type, String apiRoot,
                       String sessionHost, String servicesHost, int ordinal) {
        this.name = name;
        this.type = type;
        this.apiRoot = apiRoot;
        this.sessionHost = sessionHost;
        this.servicesHost = servicesHost;
        this.ordinal = ordinal;
    }

    public static AuthSource api(String name, String apiRoot, int ordinal) {
        String root = apiRoot;
        if (!root.endsWith("/")) {
            root = root + "/";
        }
        return new AuthSource(name, AuthSourceType.API, root, null, null, ordinal);
    }

    public static AuthSource official(String name, int ordinal) {
        return new AuthSource(name, AuthSourceType.OFFICIAL, null, null, null, ordinal);
    }

    public static AuthSource official(String name, String sessionHost, String servicesHost, int ordinal) {
        return new AuthSource(name, AuthSourceType.OFFICIAL, null, trimSlash(sessionHost), trimSlash(servicesHost), ordinal);
    }

    private static String trimSlash(String host) {
        if (host != null && host.endsWith("/")) {
            return host.substring(0, host.length() - 1);
        }
        return host;
    }

    public String name() {
        return name;
    }

    public AuthSourceType type() {
        return type;
    }

    public String apiRoot() {
        return apiRoot;
    }

    public String sessionHost() {
        return sessionHost;
    }

    public String servicesHost() {
        return servicesHost;
    }

    public int ordinal() {
        return ordinal;
    }

    public Environment toEnvironment() {
        switch (type) {
            case API -> {
                return new Environment(apiRoot + "sessionserver", apiRoot + "minecraftservices", name);
            }
            case OFFICIAL -> {
                Environment prod = YggdrasilEnvironment.PROD.getEnvironment();
                return new Environment(
                    sessionHost != null ? sessionHost : prod.sessionHost(),
                    servicesHost != null ? servicesHost : prod.servicesHost(),
                    name
                );
            }
        }
        throw new IllegalStateException("Unknown source type " + type);
    }

    /** Serializes this source into TOML lines with bilingual inline comments. */
    public List<String> serialize() {
        List<String> lines = new ArrayList<>();
        lines.add("name = " + q(name)
            + "  # 认证源名称，唯一标识 (source name, must be unique)");
        lines.add("type = " + q(type.name())
            + "  # OFFICIAL = Mojang 正版 / API = authlib-injector 兼容皮肤站(如 LittleSkin) (source type)");
        switch (type) {
            case API -> lines.add("apiRoot = " + q(apiRoot)
                + "  # API 源根地址，会话/服务地址自动推导为 <apiRoot>/sessionserver 与 <apiRoot>/minecraftservices (required when type = API)");
            case OFFICIAL -> {
                Environment prod = YggdrasilEnvironment.PROD.getEnvironment();
                if (sessionHost != null && !Objects.equals(sessionHost, prod.sessionHost())) {
                    lines.add("sessionHost = " + q(sessionHost)
                        + "  # 会话验证地址，默认 Mojang 官方 (session host, default: Mojang official)");
                }
                if (servicesHost != null && !Objects.equals(servicesHost, prod.servicesHost())) {
                    lines.add("servicesHost = " + q(servicesHost)
                        + "  # 服务主机地址，默认 Mojang 官方 (services host, default: Mojang official)");
                }
            }
        }
        lines.add("ordinal = " + ordinal
            + "  # 优先级，数字越小越先尝试 / priority, lower number is tried first");
        return lines;
    }

    private static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Rebuilds a source from one parsed TOML table. Returns {@code null}
     * when the table cannot be understood (missing/invalid fields).
     */
    public static AuthSource parse(String name, Map<String, String> kv) {
        String typeRaw = kv.get(KEY_TYPE);
        if (typeRaw == null) {
            return null;
        }
        AuthSourceType type;
        try {
            type = AuthSourceType.valueOf(typeRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }

        int ordinal;
        String ordinalRaw = kv.get(KEY_ORDINAL);
        if (ordinalRaw == null) {
            return null;
        }
        try {
            ordinal = Integer.parseInt(ordinalRaw.trim());
            if (ordinal < 0) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }

        switch (type) {
            case API -> {
                String apiRoot = kv.get(KEY_API_ROOT);
                if (apiRoot == null || apiRoot.isBlank()) {
                    return null;
                }
                return api(name, apiRoot.trim(), ordinal);
            }
            case OFFICIAL -> {
                String sessionHost = kv.get(KEY_SESSION_HOST);
                String servicesHost = kv.get(KEY_SERVICES_HOST);
                return new AuthSource(
                    name, AuthSourceType.OFFICIAL, null,
                    sessionHost != null ? trimSlash(sessionHost.trim()) : null,
                    servicesHost != null ? trimSlash(servicesHost.trim()) : null,
                    ordinal
                );
            }
        }
        return null;
    }

    @Override
    public int compareTo(AuthSource that) {
        return Integer.compare(this.ordinal, that.ordinal);
    }

    @Override
    public String toString() {
        return name + "(" + type + ", ordinal=" + ordinal + ")";
    }
}
