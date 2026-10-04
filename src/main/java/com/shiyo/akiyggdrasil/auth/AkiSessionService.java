package com.shiyo.akiyggdrasil.auth;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.Environment;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.HttpAuthenticationService;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.MinecraftClientException;
import com.mojang.authlib.minecraft.InsecurePublicKeyException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileActionType;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.authlib.yggdrasil.ServicesKeyType;
import com.mojang.authlib.yggdrasil.request.JoinMinecraftServerRequest;
import com.mojang.authlib.yggdrasil.response.HasJoinedMinecraftServerResponse;
import com.mojang.authlib.yggdrasil.response.MinecraftProfilePropertiesResponse;
import com.mojang.authlib.yggdrasil.response.MinecraftTexturesPayload;
import com.mojang.authlib.yggdrasil.response.ProfileAction;
import com.mojang.logging.LogUtils;
import com.mojang.util.UUIDTypeAdapter;
import com.mojang.util.UndashedUuid;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@link MinecraftSessionService} that checks every configured auth source
 * in ordinal order and returns the first positive answer. This is what makes
 * players from LittleSkin, self-hosted Yggdrasil servers and Mojang all able
 * to join the same server: {@code join} is recorded on every source (the one
 * that owns the token accepts it), {@code hasJoined} polls each source until
 * one recognizes the session, and profile properties are fetched the same way.
 *
 * <p>On top of that, a {@code textures} property that the peers' clients would
 * reject (because it was signed by a source they do not trust) is re-signed by
 * the configured skin site. The payload - and therefore the texture URLs - is
 * left untouched, only the signature is replaced.
 */
public final class AkiSessionService implements MinecraftSessionService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private volatile List<String> baseUrls = List.of();
    private volatile List<URL> joinUrls = List.of();
    private volatile List<URL> checkUrls = List.of();
    private volatile List<String> checkSourceNames = List.of();

    private final MinecraftClient client;
    private volatile ServicesKeySet servicesKeySet;
    private volatile SkinSigner signer;
    private final Gson gson = new GsonBuilder().registerTypeAdapter(UUID.class, new UUIDTypeAdapter()).create();
    private final LoadingCache<UUID, Optional<ProfileResult>> insecureProfiles = CacheBuilder
        .newBuilder()
        .expireAfterWrite(6, TimeUnit.HOURS)
        .build(new CacheLoader<>() {
            @Override
            public @NotNull Optional<ProfileResult> load(@NotNull UUID key) {
                return Optional.ofNullable(fetchProfileUncached(key, false));
            }
        });

    public AkiSessionService(Proxy proxy, List<Environment> envs, List<String> sourceNames,
                             ServicesKeySet servicesKeySet, SkinSigner signer) {
        this.client = MinecraftClient.unauthenticated(proxy);
        this.signer = signer;
        update(envs, sourceNames, servicesKeySet, signer);
    }

    /** Replaces the auth source endpoints and signature key set (config hot reload). */
    public void update(List<Environment> envs, List<String> sourceNames, ServicesKeySet keySet, SkinSigner signer) {
        List<String> newBase = new ArrayList<>();
        List<URL> newJoin = new ArrayList<>();
        List<URL> newCheck = new ArrayList<>();
        List<String> newCheckNames = new ArrayList<>();
        for (int i = 0; i < envs.size(); i++) {
            Environment env = envs.get(i);
            String baseUrl = env.sessionHost() + "/session/minecraft/";
            newBase.add(baseUrl);
            newJoin.add(HttpAuthenticationService.constantURL(baseUrl + "join"));
            newCheck.add(HttpAuthenticationService.constantURL(baseUrl + "hasJoined"));
            newCheckNames.add(i < sourceNames.size() ? sourceNames.get(i) : env.toString());
        }
        this.baseUrls = List.copyOf(newBase);
        this.joinUrls = List.copyOf(newJoin);
        this.checkUrls = List.copyOf(newCheck);
        this.checkSourceNames = List.copyOf(newCheckNames);
        this.servicesKeySet = keySet;
        this.signer = signer;
    }

    @Override
    public void joinServer(UUID profileId, String authenticationToken, String serverId) throws AuthenticationException {
        JoinMinecraftServerRequest request = new JoinMinecraftServerRequest(authenticationToken, profileId, serverId);
        AuthenticationException lastFailure = null;
        for (URL joinUrl : joinUrls) {
            try {
                client.post(joinUrl, request, Void.class);
                return;
            } catch (MinecraftClientException e) {
                lastFailure = e.toAuthenticationException();
                LOGGER.debug("Join request rejected by {}", joinUrl, e);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AuthenticationException("No auth sources configured");
    }

    @Override
    public ProfileResult hasJoinedServer(String profileName, String serverId, InetAddress address)
        throws AuthenticationUnavailableException {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("username", profileName);
        arguments.put("serverId", serverId);
        if (address != null) {
            arguments.put("ip", address.getHostAddress());
        }

        boolean sawUnavailable = false;
        for (int i = 0; i < checkUrls.size(); i++) {
            URL checkUrl = checkUrls.get(i);
            String sourceName = checkSourceNames.get(i);
            try {
                URL url = HttpAuthenticationService.concatenateURL(checkUrl, HttpAuthenticationService.buildQuery(arguments));
                HasJoinedMinecraftServerResponse response = client.get(url, HasJoinedMinecraftServerResponse.class);
                if (response == null || response.id() == null) {
                    // Not this source's session; keep polling the remaining sources.
                    continue;
                }
                GameProfile profile = new GameProfile(response.id(), profileName);
                if (response.properties() != null) {
                    profile.getProperties().putAll(response.properties());
                }
                String textureState = resignTextures(profile, sourceName, profileName);
                LOGGER.info("[AkiYggdrasil] 玩家 {} 由认证源 {} 放行，皮肤={}", profileName, sourceName, textureState);
                Set<ProfileActionType> profileActions = response.profileActions().stream()
                    .map(ProfileAction::type)
                    .collect(Collectors.toSet());
                return new ProfileResult(profile, profileActions);
            } catch (MinecraftClientException e) {
                if (e.toAuthenticationException() instanceof AuthenticationUnavailableException) {
                    sawUnavailable = true;
                    LOGGER.warn("Auth source {} is unavailable", checkUrl, e);
                } else {
                    LOGGER.warn("Auth source {} rejected the session", checkUrl, e);
                }
            }
        }

        // No source recognized the session. Report "auth servers down" only when
        // a source was actually unreachable; otherwise report a failed check.
        if (sawUnavailable) {
            throw new AuthenticationUnavailableException("All auth sources failed to verify the session");
        }
        return null;
    }

    /**
     * Makes the {@code textures} property acceptable to as many clients as
     * possible. Properties already signed by Mojang are left alone (official
     * launchers only trust those), everything else is re-signed by the skin site.
     *
     * @return a short status used in the login log line
     */
    private String resignTextures(GameProfile profile, String sourceName, String profileName) {
        Property property = getPackedTextures(profile);
        if (property == null) {
            return "无";
        }
        SkinSigner local = signer;
        if (local == null || !local.enabled()) {
            return "未启用重签";
        }
        if (local.skipSources().contains(sourceName)) {
            return "保留(本站)";
        }
        if (isMojangSigned(property)) {
            return "保留(Mojang)";
        }

        Optional<String> signature = local.sign(property.value());
        if (signature.isEmpty()) {
            return "重签失败";
        }
        profile.getProperties().removeAll("textures");
        profile.getProperties().put("textures", new Property("textures", property.value(), signature.get()));
        if (local.debugLog()) {
            LOGGER.info("[AkiYggdrasil] textures 调试 dump 玩家={} 源={} value={} signature={}",
                profileName, sourceName, property.value(), signature.get());
        }
        return "重签";
    }

    /** True when the bundled Mojang public key validates this property. */
    private boolean isMojangSigned(Property property) {
        if (!property.hasSignature()) {
            return false;
        }
        try {
            PublicKey mojangKey = PublicKeyUtil.loadMojangKey();
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(mojangKey);
            verifier.update(property.value().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(property.signature()));
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    @Override
    public Property getPackedTextures(GameProfile profile) {
        return Iterables.getFirst(profile.getProperties().get("textures"), null);
    }

    @Override
    public MinecraftProfileTextures unpackTextures(Property packedTextures) {
        final String value = packedTextures.value();
        final SignatureState signatureState = getPropertySignatureState(packedTextures);

        final MinecraftTexturesPayload result;
        try {
            final String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            result = gson.fromJson(json, MinecraftTexturesPayload.class);
        } catch (JsonParseException | IllegalArgumentException e) {
            LOGGER.error("Could not decode textures payload", e);
            return MinecraftProfileTextures.EMPTY;
        }

        if (result == null || result.textures() == null || result.textures().isEmpty()) {
            return MinecraftProfileTextures.EMPTY;
        }

        final Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = result.textures();
        for (final Map.Entry<MinecraftProfileTexture.Type, MinecraftProfileTexture> entry : textures.entrySet()) {
            final String url = entry.getValue().getUrl();
            if (!TextureUrlChecker.isAllowedTextureDomain(url)) {
                LOGGER.error("Textures payload contains blocked domain: {}", url);
                return MinecraftProfileTextures.EMPTY;
            }
        }

        return new MinecraftProfileTextures(
            textures.get(MinecraftProfileTexture.Type.SKIN),
            textures.get(MinecraftProfileTexture.Type.CAPE),
            textures.get(MinecraftProfileTexture.Type.ELYTRA),
            signatureState
        );
    }

    @Nullable
    @Override
    public ProfileResult fetchProfile(UUID profileId, boolean requireSecure) {
        if (!requireSecure) {
            return insecureProfiles.getUnchecked(profileId).orElse(null);
        }
        return fetchProfileUncached(profileId, true);
    }

    @Nullable
    private ProfileResult fetchProfileUncached(UUID profileId, boolean requireSecure) {
        for (String baseUrl : baseUrls) {
            try {
                URL url = HttpAuthenticationService.constantURL(baseUrl + "profile/" + UndashedUuid.toString(profileId));
                url = HttpAuthenticationService.concatenateURL(url, "unsigned=" + !requireSecure);
                MinecraftProfilePropertiesResponse response = client.get(url, MinecraftProfilePropertiesResponse.class);
                if (response == null) {
                    continue;
                }
                GameProfile profile = response.toProfile();
                Set<ProfileActionType> profileActions = response.profileActions().stream()
                    .map(ProfileAction::type)
                    .collect(Collectors.toSet());
                return new ProfileResult(profile, profileActions);
            } catch (MinecraftClientException | IllegalArgumentException e) {
                LOGGER.warn("Couldn't look up profile properties for {}", profileId, e);
            }
        }

        LOGGER.debug("Couldn't fetch profile properties for {} as no source found the profile", profileId);
        return null;
    }

    @Override
    public String getSecurePropertyValue(Property property) throws InsecurePublicKeyException {
        return switch (getPropertySignatureState(property)) {
            case UNSIGNED -> throw new InsecurePublicKeyException.MissingException("Missing signature from \"" + property.name() + "\"");
            case INVALID -> throw new InsecurePublicKeyException.InvalidException(
                "Property \"" + property.name() + "\" has been tampered with (signature invalid)");
            case SIGNED -> property.value();
        };
    }

    private SignatureState getPropertySignatureState(Property property) {
        if (!property.hasSignature()) {
            return SignatureState.UNSIGNED;
        }
        if (servicesKeySet.keys(ServicesKeyType.PROFILE_PROPERTY).stream().noneMatch(key -> {
            try {
                return key.validateProperty(property);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to validate property {}", property.name(), e);
                return false;
            }
        })) {
            return SignatureState.INVALID;
        }
        return SignatureState.SIGNED;
    }
}
