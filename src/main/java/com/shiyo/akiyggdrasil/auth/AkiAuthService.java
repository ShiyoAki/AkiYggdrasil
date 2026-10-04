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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The server's authentication service. Replaces the vanilla
 * {@link YggdrasilAuthenticationService} (created in {@code net.minecraft.server.Main},
 * redirected by {@link com.shiyo.akiyggdrasil.mixin.PhysicalServerStartMixin}) so that
 * {@link MinecraftSessionService}, {@link GameProfileRepository} and the signature
 * key set cover every configured auth source.
 *
 * <p>At construction time the signature public keys are collected: the bundled
 * Mojang key plus each API source's {@code signaturePublickey} from its
 * authlib-injector metadata document. A source whose metadata cannot be fetched
 * right now (site briefly down, slow network) is retried in the background so a
 * transient outage no longer leaves that source's players with permanently
 * unverifiable textures.
 */
public final class AkiAuthService extends YggdrasilAuthenticationService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(8);
    private static final long KEY_RETRY_INTERVAL_MS = 60_000L;
    private static final int KEY_RETRY_ATTEMPTS = 10;

    private volatile List<Environment> environments;
    private volatile List<String> sourceNames;
    private volatile ServicesKeySet servicesKeySet;
    private volatile SkinSigner signer;
    private volatile AkiSessionService sessionService;
    private volatile AkiGameProfileRepository profileRepository;
    private volatile boolean reloadEnabled;
    private final List<PublicKey> keys = new CopyOnWriteArrayList<>();

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
        List<Environment> envs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AuthSource source : config.sources()) {
            envs.add(source.toEnvironment());
            names.add(source.name());
        }
        this.environments = List.copyOf(envs);
        this.sourceNames = List.copyOf(names);
        this.signer = new SkinSigner(config.signer());

        this.keys.clear();
        try {
            keys.add(PublicKeyUtil.loadMojangKey());
        } catch (Exception e) {
            LOGGER.error("[AkiYggdrasil] Failed to load the bundled Mojang public key", e);
        }
        Set<String> missing = collectApiKeys(config.sources());
        refreshKeySet();

        LOGGER.info("[AkiYggdrasil] Auth environments: {}", environments);
        if (!missing.isEmpty()) {
            LOGGER.warn("[AkiYggdrasil] No signature key yet for {}, will retry in the background", missing);
            scheduleKeyRetry(config, missing);
        }
    }

    private void onConfigReload(AkiConfig config) {
        applyConfig(config);
        AkiGameProfileRepository repo = profileRepository;
        if (repo != null) {
            repo.update(environments);
        }
    }

    public List<Environment> environments() {
        return environments;
    }

    /** Fetches each API source's signature public key. Returns the names it could not get. */
    private Set<String> collectApiKeys(List<AuthSource> sources) {
        Set<String> missing = new LinkedHashSet<>();
        YggdrasilApi api = new YggdrasilApi(METADATA_TIMEOUT);
        for (AuthSource source : sources) {
            if (source.type() != AuthSourceType.API) {
                continue;
            }
            Optional<YggdrasilApi.Metadata> metadata = api.fetchMetadata(source.apiRoot());
            if (metadata.isEmpty()) {
                LOGGER.warn("[AkiYggdrasil] Could not fetch metadata from {} ({})", source.name(), source.apiRoot());
                missing.add(source.name());
                continue;
            }
            String pem = metadata.get().signaturePublickey();
            if (pem == null || pem.isBlank()) {
                LOGGER.warn("[AkiYggdrasil] Source {} does not publish a signature public key", source.name());
                missing.add(source.name());
                continue;
            }
            try {
                keys.add(PublicKeyUtil.parsePem(pem));
                LOGGER.info("[AkiYggdrasil] Fetched signature public key from {}", source.name());
            } catch (Exception e) {
                LOGGER.warn("[AkiYggdrasil] Invalid signature public key from {}", source.name(), e);
                missing.add(source.name());
            }
        }
        return missing;
    }

    private void scheduleKeyRetry(AkiConfig config, Set<String> missing) {
        Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < KEY_RETRY_ATTEMPTS; attempt++) {
                try {
                    Thread.sleep(KEY_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                List<AuthSource> pending = config.sources().stream()
                    .filter(s -> missing.contains(s.name()))
                    .toList();
                Set<String> stillMissing = collectApiKeys(pending);
                missing.retainAll(stillMissing);
                if (missing.isEmpty()) {
                    LOGGER.info("[AkiYggdrasil] All signature keys are now available");
                    return;
                }
                refreshKeySet();
                LOGGER.warn("[AkiYggdrasil] Still missing signature keys for {}", missing);
            }
        }, "AkiYggdrasil-KeyRetry");
        thread.setDaemon(true);
        thread.start();
    }

    /** Rebuilds the key set view and pushes it into the live session service. */
    private void refreshKeySet() {
        this.servicesKeySet = type -> List.of(new AkiServicesKeyInfo(List.copyOf(keys)));
        AkiSessionService session = sessionService;
        if (session != null) {
            session.update(environments, sourceNames, servicesKeySet, signer);
        }
    }

    @Override
    public MinecraftSessionService createMinecraftSessionService() {
        AkiSessionService service = new AkiSessionService(getProxy(), environments, sourceNames, servicesKeySet, signer);
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
