package com.shiyo.akiyggdrasil.auth;

import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ServicesKeyInfo;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/**
 * Verifies profile-property signatures against every known public key
 * (the bundled Mojang key plus the signature keys of all configured API
 * sources), so textures/signed properties from any source are accepted.
 *
 * <p>{@link #signature()} intentionally returns a {@link DummySignature}:
 * third-party auth servers do not sign chat profile keys like Mojang does,
 * so the server-side {@code PROFILE_KEY} validator accepts whatever the
 * client sends, keeping secure-chat plumbing working for every source.
 */
public final class AkiServicesKeyInfo implements ServicesKeyInfo {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<PublicKey> keys;

    public AkiServicesKeyInfo(List<PublicKey> keys) {
        this.keys = List.copyOf(keys);
    }

    public List<PublicKey> keys() {
        return keys;
    }

    @Override
    public int keyBitCount() {
        return 4096;
    }

    @Override
    public Signature signature() {
        return new DummySignature();
    }

    @Override
    public boolean validateProperty(Property property) {
        byte[] data = property.value().getBytes(StandardCharsets.UTF_8);
        final byte[] signature;
        try {
            signature = Base64.getDecoder().decode(property.signature());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Property {} has a malformed signature", property.name());
            return false;
        }

        for (PublicKey key : keys) {
            try {
                Signature verifier = Signature.getInstance("SHA1withRSA");
                verifier.initVerify(key);
                verifier.update(data);
                if (verifier.verify(signature)) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                LOGGER.debug("Failed to verify property signature with a key", e);
            }
        }
        LOGGER.debug("Failed to verify property {} signature with all keys", property.name());
        return false;
    }
}
