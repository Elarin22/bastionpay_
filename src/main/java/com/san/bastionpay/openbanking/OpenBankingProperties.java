package com.san.bastionpay.openbanking;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.openbanking")
public record OpenBankingProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String redirectUri,
        String orgCode,
        String tokenSecret,
        Duration stateTtl,
        Duration connectTimeout,
        Duration readTimeout,
        String transferPassPhrase) {

    /** 인증 정보가 채워져 있어야 연결을 시작할 수 있다. */
    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret);
    }

    /** 잔액조회는 이용기관코드로 거래고유번호를 만들어야 한다. */
    public boolean canQueryBalance() {
        return orgCode != null && orgCode.length() == 10;
    }

    /** 이체는 이용기관코드와 입금이체 암호문구가 모두 있어야 한다. */
    public boolean canTransfer() {
        return canQueryBalance() && notBlank(transferPassPhrase);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** client_secret 이 로그에 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "OpenBankingProperties[baseUrl=" + baseUrl + ", redirectUri=" + redirectUri + ", configured=" + isConfigured() + "]";
    }
}
