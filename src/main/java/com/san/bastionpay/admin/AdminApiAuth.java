package com.san.bastionpay.admin;

import com.san.bastionpay.audit.Actor;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 서버 간 호출(n8n·회수봇)의 인증. 헤더 X-API-Key 가 설정한 키와 같을 때만 통과한다.
 * 키가 설정되지 않았으면 어떤 요청도 통과시키지 않는다(fail-closed). 시도한 키 값은 로그에 남기지 않는다.
 */
@Component
public class AdminApiAuth {

    public static final String HEADER = "X-API-Key";

    private final AdminSecurityProperties props;
    private final SecurityEventRecorder events;

    public AdminApiAuth(AdminSecurityProperties props, SecurityEventRecorder events) {
        this.props = props;
        this.events = events;
    }

    /** 유효한 키를 가진 요청인가. 이벤트는 남기지 않는다(IP 차단 필터가 예외 판정에 쓴다). */
    public boolean matches(HttpServletRequest request) {
        if (!props.apiEnabled()) return false;
        String given = request.getHeader(HEADER);
        if (given == null) return false;
        // 길이가 달라도 같은 시간이 걸리도록 해시로 비교한다
        return MessageDigest.isEqual(sha256(given), sha256(props.apiKey()));
    }

    /** 키를 확인한다. 실패하면 API_AUTH_FAILED 를 남기고 비어 있다(키를 추측해 두드리는 시도를 탐지할 수 있다). */
    public Optional<Actor> authenticate(HttpServletRequest request) {
        if (matches(request)) return Optional.of(Actor.api());
        events.record(SecurityEvent.of(SecurityEventType.API_AUTH_FAILED, EventOutcome.DENIED)
                .reasonCode(props.apiEnabled() ? "BAD_API_KEY" : "API_DISABLED")
                .request(request.getMethod(), request.getRequestURI()));
        return Optional.empty();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
