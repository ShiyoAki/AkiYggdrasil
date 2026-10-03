package com.shiyo.akiyggdrasil.config;

/**
 * Kind of an authentication source.
 *
 * <p>{@link #OFFICIAL} is Mojang's own Yggdrasil deployment (or a compatible
 * server that exposes separate session/services hosts). {@link #API} is an
 * authlib-injector compatible server, configured by its API root URL.
 */
public enum AuthSourceType {
    OFFICIAL,
    API
}
