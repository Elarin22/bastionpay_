package com.san.mockbank.phone;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("mockbank.phone-auth")
public record PhoneAuthProperties(Duration codeTtl, int maxAttempts, Duration resendInterval, int maxSendsPerHour) {
}
