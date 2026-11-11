package com.san.bastionpay.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration refreshReuseGrace,
        Cookie cookie) {

    public record Cookie(boolean secure, String sameSite) {
    }
}
