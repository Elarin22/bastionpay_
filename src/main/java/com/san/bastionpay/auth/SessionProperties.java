package com.san.bastionpay.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 로그인 세션 설정.
 *
 * @param idleTimeout   마지막 활동 후 이 시간이 지나면 다시 로그인한다(활동하면 계속 늘어난다)
 * @param touchInterval 활동을 DB 에 반영하는 최소 간격(너무 잦은 쓰기를 막는다). 남은 시간 표시는 이만큼까지 어긋날 수 있다
 */
@ConfigurationProperties("app.session")
public record SessionProperties(Duration idleTimeout, Duration touchInterval) {

    public SessionProperties {
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) idleTimeout = Duration.ofMinutes(30);
        if (touchInterval == null || touchInterval.isNegative()) touchInterval = Duration.ofSeconds(5);
    }
}
