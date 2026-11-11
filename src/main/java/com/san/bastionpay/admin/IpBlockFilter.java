package com.san.bastionpay.admin;

import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 차단된 IP 의 요청을 앱에 닿기 전에 403 으로 돌려보낸다(MdcLoggingFilter 다음이라 출발지 IP·추적 ID 가 이미 정해져 있다).
 * 유효한 API 키를 가진 자동화 요청은 차단의 영향을 받지 않는다 — 같은 호스트의 자동화가 자기 IP 차단으로 멈추는 것을 막는다.
 * 차단된 IP 가 계속 두드려도 이벤트가 쏟아지지 않도록, 같은 IP 의 이벤트는 1분에 한 번만 남긴다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IpBlockFilter extends OncePerRequestFilter {

    private static final long RECORD_INTERVAL_MS = 60_000;
    private static final int MAX_TRACKED = 10_000;

    private final BlockedIpService blocked;
    private final AdminApiAuth apiAuth;
    private final SecurityEventRecorder events;
    private final ConcurrentHashMap<String, Long> lastRecorded = new ConcurrentHashMap<>();

    public IpBlockFilter(BlockedIpService blocked, AdminApiAuth apiAuth, SecurityEventRecorder events) {
        this.blocked = blocked;
        this.apiAuth = apiAuth;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = MDC.get("clientIp");
        if (ip != null && !isStaticResource(request) && blocked.isBlocked(ip) && !apiAuth.matches(request)) {
            record(ip, request);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 403 에러 페이지가 쓰는 공개 정적 파일. 이것까지 막으면 차단 안내 화면의 CSS 가 깨진다. */
    private static boolean isStaticResource(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/css/") || path.startsWith("/js/")
                || path.startsWith("/img/") || path.startsWith("/fonts/");
    }

    private void record(String ip, HttpServletRequest request) {
        long now = System.currentTimeMillis();
        if (lastRecorded.size() > MAX_TRACKED) lastRecorded.clear();
        Long last = lastRecorded.put(ip, now);
        if (last != null && now - last < RECORD_INTERVAL_MS) {
            lastRecorded.put(ip, last);   // 간격 안에서는 시각을 갱신하지 않는다(1분마다 한 번 남도록)
            return;
        }
        events.record(SecurityEvent.of(SecurityEventType.IP_BLOCKED_REQUEST, EventOutcome.DENIED)
                .reasonCode("IP_BLOCKED").request(request.getMethod(), request.getRequestURI()));
    }
}
