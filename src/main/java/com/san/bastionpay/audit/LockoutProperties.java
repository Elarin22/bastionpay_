package com.san.bastionpay.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 로그인 실패 자동 잠금 설정. 합성 테스트 계정(allowlist)에만 적용한다 — 실제 가입 계정과 관리자 계정은 잠그지 않는다.
 *
 * @param enabled      false 면 실패를 세지도 잠그지도 않는다
 * @param maxFailures  시간창 안에서 이 횟수만큼 실패하면 잠근다
 * @param window       실패를 세는 시간창
 * @param lockDuration 잠금 시간
 * @param allowlist    잠금 대상 이메일(대소문자 무시)
 */
@ConfigurationProperties(prefix = "app.security.lockout")
public record LockoutProperties(boolean enabled, int maxFailures, Duration window, Duration lockDuration, List<String> allowlist) {

    public LockoutProperties {
        if (maxFailures < 1) throw new IllegalArgumentException("app.security.lockout.max-failures 는 1 이상이어야 합니다.");
        if (window == null || window.isNegative() || window.isZero()) window = Duration.ofMinutes(10);
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) lockDuration = Duration.ofMinutes(15);
        allowlist = allowlist == null ? List.of()
                : allowlist.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    public boolean covers(String email) {
        return enabled && email != null && allowlist.contains(email.trim().toLowerCase(Locale.ROOT));
    }
}
