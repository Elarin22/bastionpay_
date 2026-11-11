package com.san.bastionpay.signup;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.signup")
public record SignupProperties(
        Duration codeTtl,
        int codeMaxAttempts,
        Duration resendInterval,
        Duration requestTtl) {
}
