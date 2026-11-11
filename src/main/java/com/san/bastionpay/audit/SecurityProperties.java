package com.san.bastionpay.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 보안 이벤트 설정.
 *
 * @param trustedProxies  X-Forwarded-For 를 믿는 프록시의 IP 목록(정확히 일치). 비어 있으면 헤더를 쓰지 않는다
 * @param subjectHashKey  로그인 계정 식별값(HMAC)을 만들 때 쓰는 서버 비밀키
 * @param eventDbEnabled  false 면 보안 이벤트를 DB 에 저장하지 않는다(기본 true)
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(List<String> trustedProxies, String subjectHashKey, Boolean eventDbEnabled) {

    public SecurityProperties {
        eventDbEnabled = eventDbEnabled == null || eventDbEnabled;
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public String toString() {
        return "SecurityProperties[trustedProxies=" + trustedProxies + ", subjectHashKey=***]";
    }
}
