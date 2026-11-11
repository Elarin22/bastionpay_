package com.san.mockbank.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("mockbank")
public record MockbankProperties(Duration authCodeTtl, Duration accessTokenTtl, long transferMaxAmount) {
}
