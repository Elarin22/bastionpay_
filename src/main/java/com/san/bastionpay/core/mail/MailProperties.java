package com.san.bastionpay.core.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mail")
public record MailProperties(String from, boolean logCode) {
}
