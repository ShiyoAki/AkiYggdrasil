package com.shiyo.akiyggdrasil.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal Yggdrasil protocol client (pure JDK HTTP + Gson, no Minecraft
 * classes) implementing the endpoints described in the authlib-injector
 * Yggdrasil server spec. Used for fetching metadata/signature keys at
 * startup and by the automated tests to drive a real login session.
 */
public final class YggdrasilApi {

    private static final Gson GSON = new Gson();
    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<GameProfileData>>() {}.getType();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;

    public YggdrasilApi() {
        this(Proxy.NO_PROXY);
    }

    public YggdrasilApi(Proxy proxy) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .proxy(toProxySelector(proxy))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    private static java.net.ProxySelector toProxySelector(Proxy proxy) {
        if (proxy == null || proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) {
            return java.net.ProxySelector.of(null);
        }
        if (proxy.address() instanceof InetSocketAddress addr) {
            return java.net.ProxySelector.of(addr);
        }
        return java.net.ProxySelector.of(null);
    }

    private static String apiPath(String apiRoot, String path) {
        if (!apiRoot.endsWith("/")) {
            apiRoot = apiRoot + "/";
        }
        return apiRoot + path;
    }

    // ---------------------------------------------------------------- metadata

    /**
     * Fetches the authlib-injector metadata document of an API source.
     * Returns empty when the server does not answer with a valid document.
     */
    public Optional<Metadata> fetchMetadata(String apiRoot) {
        try {
            String body = get(apiPath(apiRoot, ""));
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String pubkey = jsonString(root.get("signaturePublickey"));
            List<String> skinDomains = new ArrayList<>();
            if (root.has("skinDomains") && root.get("skinDomains").isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray("skinDomains")) {
                    skinDomains.add(e.getAsString());
                }
            }
            boolean nonEmailLogin = root.has("meta")
                && root.get("meta").isJsonObject()
                && jsonBool(root.getAsJsonObject("meta").get("feature.non_email_login"));
            return Optional.of(new Metadata(pubkey, skinDomains, nonEmailLogin));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- auth API

    public Optional<AuthResult> authenticate(String apiRoot, String username, String password, String clientToken) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        if (clientToken != null) {
            body.addProperty("clientToken", clientToken);
        }
        body.addProperty("requestUser", false);
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        body.add("agent", agent);

        try {
            return Optional.of(GSON.fromJson(post(apiPath(apiRoot, "authserver/authenticate"), body), AuthResult.class));
        } catch (YggdrasilApiException e) {
            return Optional.empty();
        }
    }

    public boolean validate(String apiRoot, String accessToken, String clientToken) {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        if (clientToken != null) {
            body.addProperty("clientToken", clientToken);
        }
        return postNoContent(apiPath(apiRoot, "authserver/validate"), body);
    }

    public boolean invalidate(String apiRoot, String accessToken, String clientToken) {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        if (clientToken != null) {
            body.addProperty("clientToken", clientToken);
        }
        return postNoContent(apiPath(apiRoot, "authserver/invalidate"), body);
    }

    // ------------------------------------------------------------ session API

    /** Records the server join (POST /sessionserver/session/minecraft/join). */
    public boolean join(String apiRoot, String accessToken, String selectedProfileUuid, String serverId) {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("selectedProfile", selectedProfileUuid);
        body.addProperty("serverId", serverId);
        return postNoContent(apiPath(apiRoot, "sessionserver/session/minecraft/join"), body);
    }

    /** Returns the profile when the session is valid, empty on a miss. */
    public Optional<GameProfileData> hasJoined(String apiRoot, String username, String serverId, String ip) {
        try {
            String path = "sessionserver/session/minecraft/hasJoined?username=" + encode(username)
                + "&serverId=" + encode(serverId);
            if (ip != null && !ip.isEmpty()) {
                path = path + "&ip=" + encode(ip);
            }
            String body = get(apiPath(apiRoot, path));
            if (body == null || body.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(GSON.fromJson(body, GameProfileData.class));
        } catch (YggdrasilApiException e) {
            return Optional.empty();
        }
    }

    /** Fetches a profile with (unsigned=false) or without (unsigned=true) signed properties. */
    public Optional<GameProfileData> fetchProfile(String apiRoot, String uuidUndashed, boolean unsigned) {
        try {
            String body = get(apiPath(apiRoot, "sessionserver/session/minecraft/profile/" + uuidUndashed
                + "?unsigned=" + unsigned));
            if (body == null || body.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(GSON.fromJson(body, GameProfileData.class));
        } catch (YggdrasilApiException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------- services API

    public List<GameProfileData> lookupByNames(String apiRoot, List<String> names) {
        try {
            String body = post(apiPath(apiRoot, "minecraftservices/minecraft/profile/lookup/bulk/byname"),
                GSON.toJson(names));
            if (body == null || body.isEmpty()) {
                return List.of();
            }
            List<GameProfileData> profiles = GSON.fromJson(body, PROFILE_LIST_TYPE);
            return profiles == null ? List.of() : profiles;
        } catch (YggdrasilApiException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String get(String url) throws YggdrasilApiException {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(url)).GET().timeout(TIMEOUT).build();
            return send(request);
        } catch (IOException e) {
            throw new YggdrasilApiException("Network error on GET " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YggdrasilApiException("Interrupted on GET " + url, e);
        }
    }

    private String post(String url, Object jsonBody) throws YggdrasilApiException {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(url))
                .POST(HttpRequest.BodyPublishers.ofString(
                    jsonBody instanceof String s ? s : GSON.toJson(jsonBody), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(TIMEOUT)
                .build();
            return send(request);
        } catch (IOException e) {
            throw new YggdrasilApiException("Network error on POST " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YggdrasilApiException("Interrupted on POST " + url, e);
        }
    }

    private boolean postNoContent(String url, Object jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(url))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonBody), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(TIMEOUT)
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        String body = response.body();
        if (code / 100 == 2) {
            return body == null || body.isEmpty() ? null : body;
        }
        throw new YggdrasilApiException(parseError(code, body));
    }

    private static String parseError(int code, String body) {
        if (body == null || body.isEmpty()) {
            return "HTTP " + code;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String error = jsonString(root.get("error"));
            String message = jsonString(root.get("errorMessage"));
            if (error != null || message != null) {
                return "HTTP " + code + ": " + error + " - " + message;
            }
        } catch (RuntimeException ignored) {
            // not a JSON error body
        }
        return "HTTP " + code;
    }

    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL " + url, e);
        }
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String jsonString(JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean jsonBool(JsonElement e) {
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    // ---------------------------------------------------------------- records

    /** authlib-injector metadata document (GET /). */
    public record Metadata(String signaturePublickey, List<String> skinDomains, boolean nonEmailLogin) {
    }

    /** authenticate / refresh / hasJoined response profile. */
    public record GameProfileData(String id, String name, List<PropertyData> properties) {

        public String uuidUndashed() {
            return id;
        }

        public static GameProfileData fromMap(Map<String, Object> map) {
            String id = String.valueOf(map.get("id"));
            String name = String.valueOf(map.get("name"));
            List<PropertyData> props = new ArrayList<>();
            Object rawProps = map.get("properties");
            if (rawProps instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object value = m.get("value");
                        Object signature = m.get("signature");
                        props.add(new PropertyData(
                            String.valueOf(m.get("name")),
                            value == null ? null : String.valueOf(value),
                            signature == null ? null : String.valueOf(signature)
                        ));
                    }
                }
            }
            return new GameProfileData(id, name, props);
        }
    }

    public record PropertyData(String name, String value, String signature) {
    }

    public record AuthResult(String accessToken, String clientToken,
                             List<GameProfileData> availableProfiles, GameProfileData selectedProfile) {
    }

    public static class YggdrasilApiException extends RuntimeException {
        public YggdrasilApiException(String message) {
            super(message);
        }

        public YggdrasilApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
