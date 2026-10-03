package com.shiyo.akiyggdrasil.auth;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Texture URL policy. Third-party auth servers serve textures from their own
 * domains, so instead of the vanilla whitelist only a small set of known-bad
 * Mojang domains is blocked (mirroring what the client-side whitelist covers).
 */
public final class TextureUrlChecker {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final List<String> BLOCKED_DOMAINS = List.of(
        "bugs.mojang.com",
        "education.minecraft.net",
        "feedback.minecraft.net"
    );

    private TextureUrlChecker() {
    }

    public static boolean isAllowedTextureDomain(String url) {
        final URI uri;
        try {
            uri = new URI(url).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid texture URL '" + url + "'", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            return false;
        }
        String domain = uri.getHost();
        if (domain == null) {
            return false;
        }

        String unicode = IDN.toUnicode(domain);
        String lower = unicode.toLowerCase(Locale.ROOT);
        if (!lower.equals(unicode)) {
            return false;
        }
        return !isBlocked(unicode);
    }

    private static boolean isBlocked(String domain) {
        for (String entry : BLOCKED_DOMAINS) {
            if (domain.endsWith(entry)) {
                return true;
            }
        }
        return false;
    }
}
