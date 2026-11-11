package com.san.bastionpay.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * 관리자 보안 기능 설정.
 *
 * @param apiKey         서버 간 호출(n8n·회수봇)용 키. 비어 있으면 API 는 모든 요청을 401 로 거부한다(fail-closed)
 * @param adminAllowlist 관리자 권한을 가져도 되는 계정(이메일). 이 밖의 관리자는 "과잉권한"으로 표시한다
 */
@ConfigurationProperties(prefix = "app.security.admin")
public record AdminSecurityProperties(String apiKey, List<String> adminAllowlist) {

    public AdminSecurityProperties {
        adminAllowlist = adminAllowlist == null ? List.of()
                : adminAllowlist.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    public boolean apiEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "AdminSecurityProperties[apiKey=" + (apiEnabled() ? "***" : "(unset)") + ", adminAllowlist=" + adminAllowlist + "]";
    }
}
