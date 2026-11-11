package com.san.mockbank.phone;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mockbank.sms")
public record SmsProperties(boolean enabled, String apiKey, String apiSecret, String senderNumber, boolean showCodeOnPage) {

    /** 키가 로그에 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "SmsProperties[enabled=" + enabled + ", showCodeOnPage=" + showCodeOnPage + "]";
    }
}
