package com.shiyo.akiyggdrasil;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Server-side entry point. All authentication logic lives in the auth service
 * that the {@code PhysicalServerStartMixin} redirects the dedicated server to
 * create; this class only marks the mod for loading.
 */
@Mod("akiyggdrasil")
public final class AkiYggdrasil {

    private static final Logger LOGGER = LogUtils.getLogger();

    public AkiYggdrasil() {
        LOGGER.info("[AkiYggdrasil] Loaded (server-side multi-Yggdrasil authentication)");
    }
}
