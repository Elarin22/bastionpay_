package com.san.bastionpay.core.security;

import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 미인증 접근(401) 처리.
 * 화면 이동 요청은 /login 으로 보내고, fetch/htmx 요청은 상태 코드만 돌려준다.
 * 원래 경로를 URL 파라미터로 붙이지 않는다(오픈 리다이렉트 방지). 로그인 후에는 홈으로 간다.
 */
@Slf4j
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityEventRecorder events;

    public CustomAuthenticationEntryPoint(SecurityEventRecorder events) {
        this.events = events;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        events.record(SecurityEvent.of(SecurityEventType.AUTHENTICATION_REQUIRED, EventOutcome.DENIED)
                .reasonCode("NOT_AUTHENTICATED").request(request.getMethod(), request.getRequestURI()));
        if (isPageRequest(request)) {
            // 활동이 없어 세션이 만료된 경우에는 그 이유를 로그인 화면에서 알려 준다
            boolean expired = request.getAttribute(com.san.bastionpay.auth.JwtAuthenticationFilter.SESSION_EXPIRED) != null;
            response.sendRedirect(request.getContextPath() + (expired ? "/login?expired" : "/login?required"));
        } else {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        }
    }

    /** 브라우저의 화면 이동은 Accept 에 text/html 이 들어 있다. fetch 기본값(*&#47;*)과 htmx 는 제외한다. */
    static boolean isPageRequest(HttpServletRequest request) {
        if (request.getHeader("HX-Request") != null) return false;
        if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) return false;
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }
}
