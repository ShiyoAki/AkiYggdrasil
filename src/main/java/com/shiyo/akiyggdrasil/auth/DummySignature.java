package com.shiyo.akiyggdrasil.auth;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Signature that accepts every input. Used for the {@code PROFILE_KEY} key
 * type: third-party auth servers do not sign chat profile keys, so the
 * server-side chat signature validator simply accepts whatever the client
 * sends (vanilla-style "no validation" while keeping Secure Chat plumbing).
 */
public class DummySignature extends Signature {

    public DummySignature() {
        super("dummy-verify");
        state = VERIFY;
    }

    @Override
    protected void engineInitVerify(PublicKey publicKey) {
    }

    @Override
    protected void engineInitSign(PrivateKey privateKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void engineUpdate(byte b) {
    }

    @Override
    protected void engineUpdate(byte[] b, int off, int len) {
    }

    @Override
    protected byte[] engineSign() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean engineVerify(byte[] sigBytes) {
        return true;
    }

    @Override
    @Deprecated
    protected void engineSetParameter(String param, Object value) {
    }

    @Override
    @Deprecated
    protected Object engineGetParameter(String param) {
        return null;
    }
}
