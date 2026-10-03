package com.shiyo.akiyggdrasil.auth;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA public key parsing helpers (X.509 DER and PEM), used to build the
 * signature verification key set from the bundled Mojang key and from each
 * API source's {@code signaturePublickey} metadata field.
 */
public final class PublicKeyUtil {

    private static final String PEM_HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String PEM_FOOTER = "-----END PUBLIC KEY-----";

    private PublicKeyUtil() {
    }

    public static PublicKey parseDer(byte[] der) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    public static PublicKey parsePem(String pem) throws GeneralSecurityException {
        String cleaned = pem.replace("\n", "").replace("\r", "").trim();
        if (!cleaned.startsWith(PEM_HEADER) || !cleaned.endsWith(PEM_FOOTER)) {
            throw new IllegalArgumentException("Bad PEM public key format");
        }
        String base64 = cleaned.substring(PEM_HEADER.length(), cleaned.length() - PEM_FOOTER.length());
        return parseDer(Base64.getDecoder().decode(base64));
    }

    /** Reads the bundled Mojang session public key (X.509 DER resource). */
    public static PublicKey loadMojangKey() throws IOException, GeneralSecurityException {
        try (InputStream in = PublicKeyUtil.class.getResourceAsStream("/yggdrasil_session_pubkey.der")) {
            if (in == null) {
                throw new IOException("Missing bundled yggdrasil_session_pubkey.der");
            }
            return parseDer(in.readAllBytes());
        }
    }
}
