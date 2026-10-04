package com.shiyo.akiyggdrasil.auth;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.shiyo.akiyggdrasil.config.SignerSettings;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Client of the skin site's remote signing endpoint.
 *
 * <p>A {@code textures} property is only rendered by clients that trust the key
 * which signed it. Players arriving from a foreign Yggdrasil source therefore
 * show up as default Steve/Alex for everybody else, even though the server does
 * relay their payload. Asking the skin site to stamp the very same payload with
 * its own key fixes that without touching the payload or the texture URLs.
 *
 * <p>Results are cached by payload, and every failure degrades to "keep the
 * original property" - signing must never keep somebody out of the server.
 */
public final class SkinSigner {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PAYLOAD_LENGTH = 100_000;

    private final SignerSettings settings;
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final Cache<String, String> cache = CacheBuilder.newBuilder()
        .maximumSize(4096)
        .expireAfterWrite(6, TimeUnit.HOURS)
        .build();

    public SkinSigner(SignerSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(settings.timeoutMs()))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public List<String> skipSources() {
        return settings.skipSources();
    }

    public boolean debugLog() {
        return settings.debugLog();
    }

    /** Returns the base64 signature for the given textures payload, or empty on any failure. */
    public Optional<String> sign(String payload) {
        if (!settings.enabled() || payload == null || payload.isBlank()
            || payload.length() > MAX_PAYLOAD_LENGTH) {
            return Optional.empty();
        }

        String cached = cache.getIfPresent(payload);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<String> signature = callEndpoint(payload);
        signature.ifPresent(value -> cache.put(payload, value));
        return signature;
    }

    private Optional<String> callEndpoint(String payload) {
        JsonObject body = new JsonObject();
        body.addProperty("value", payload);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(settings.endpoint()))
                .timeout(Duration.ofMillis(settings.timeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
            if (!settings.token().isBlank()) {
                builder.header("Authorization", "Bearer " + settings.token());
            }

            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOGGER.warn("[AkiYggdrasil] Signer returned HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("signature")) {
                LOGGER.warn("[AkiYggdrasil] Signer response has no 'signature' field");
                return Optional.empty();
            }
            String signature = root.get("signature").getAsString();
            if (signature.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(signature);
        } catch (Exception e) {
            LOGGER.warn("[AkiYggdrasil] Signer request failed, keeping the original property: {}", e.toString());
            return Optional.empty();
        }
    }
}
