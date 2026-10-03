package com.shiyo.akiyggdrasil.auth;

import com.mojang.authlib.Environment;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.logging.LogUtils;
import com.shiyo.akiyggdrasil.api.YggdrasilApi;
import com.shiyo.akiyggdrasil.config.AkiConfig;
import com.shiyo.akiyggdrasil.config.AuthSource;
import com.shiyo.akiyggdrasil.config.AuthSourceType;
import org.slf4j.Logger;

import java.net.Proxy;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The server's authentication service. Replaces the vanilla
 * {@link YggdrasilAuthenticationService} (created in {@code net.minecraft.server.Main},
 * redirected by {@link com.shiyo.akiyggdrasil.mixin.PhysicalServerStartMixin}) so that
 * {@link MinecraftSessionService}, {@link GameProfileRepository} and the signature
 * key set cover every configured auth source.
 *
 * <p>At construction time the signature public keys are collected: the bundled
 * Mojang key plus each API source's {@code signaturePublickey} from its
 * authlib-injector metadata document.
 */
public final class AkiAuthService extends YggdrasilAuthenticationService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private volatile List<Environment> environments;
    private volatile ServicesKeySet servicesKeySet;
    private volatile AkiSessionService sessionService;
    private volatile AkiGameProfileRepository profileRepository;
    private volatile boolean reloadEnabled;

    public AkiAuthService(Proxy proxy) {
        this(proxy, AkiConfig.get());
    }

    public AkiAuthService(Proxy proxy, AkiConfig config) {
        super(proxy);
        applyConfig(config);
    }

    /**
     * Starts the config file watcher and applies subsequent changes to the
     * live session/profile services. Called once from the dedicated-server
     * entry point, after the auth service is wired into {@code Services}.
     */
    public synchronized void enableReload() {
        if (reloadEnabled) {
            return;
        }
        reloadEnabled = true;
        AkiConfig.addReloadListener(this::onConfigReload);
        AkiConfig.startWatching();
    }

    private void applyConfig(AkiConfig config) {
        this.environments = config.sources().stream().map(AuthSource::toEnvironment).toList();
        this.servicesKeySet = buildServicesKeySet(config);
        LOGGER.info("[AkiYggdrasil] Auth environments: {}", environments);
    }

    private void onConfigReload(AkiConfig config) {
        applyConfig(config);
        AkiSessionService session = sessionService;
        if (session != null) {
            session.update(environments, servicesKeySet);
        }
        AkiGameProfileRepository repo = profileRepository;
        if (repo != null) {
            repo.update(environments);
        }
    }

    public List<Environment> environments() {
        return environments;
    }

    private static ServicesKeySet buildServicesKeySet(AkiConfig config) {
        List<PublicKey> keys = new ArrayList<>();
        try {
            keys.add(PublicKeyUtil.loadMojangKey());
        } catch (Exception e) {
            LOGGER.error("[AkiYggdrasil] Failed to load the bundled Mojang public key", e);
        }

        YggdrasilApi api = new YggdrasilApi();
        for (AuthSource source : config.sources()) {
            if (source.type() != AuthSourceType.API) {
                continue;
            }
            Optional<YggdrasilApi.Metadata> metadata = api.fetchMetadata(source.apiRoot());
            if (metadata.isEmpty()) {
                LOGGER.warn("[AkiYggdrasil] Could not fetch metadata from {} ({})", source.name(), source.apiRoot());
                continue;
            }
            String pem = metadata.get().signaturePublickey();
            if (pem == null || pem.isBlank()) {
                LOGGER.warn("[AkiYggdrasil] Source {} does not publish a signature public key", source.name());
                continue;
            }
            try {
                keys.add(PublicKeyUtil.parsePem(pem));
                LOGGER.info("[AkiYggdrasil] Fetched signature public key from {}", source.name());
            } catch (Exception e) {
                LOGGER.warn("[AkiYggdrasil] Invalid signature public key from {}", source.name(), e);
            }
        }

        AkiServicesKeyInfo info = new AkiServicesKeyInfo(keys);
        return type -> List.of(info);
    }

    @Override
    public MinecraftSessionService createMinecraftSessionService() {
        AkiSessionService service = new AkiSessionService(getProxy(), environments, servicesKeySet);
        this.sessionService = service;
        return service;
    }

    @Override
    public GameProfileRepository createProfileRepository() {
        AkiGameProfileRepository repository = new AkiGameProfileRepository(getProxy(), environments);
        this.profileRepository = repository;
        return repository;
    }

    @Override
    public UserApiService createUserApiService(String accessToken) {
        return UserApiService.OFFLINE;
    }

    @Override
    public ServicesKeySet getServicesKeySet() {
        return servicesKeySet;
    }
}
