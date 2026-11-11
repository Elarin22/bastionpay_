package com.san.bastionpay.core.security;

import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.auth.AuthMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 403 처리. 권한 부족과 CSRF 검증 실패를 서로 다른 이벤트 타입으로 기록한다(탐지 룰이 구분할 수 있게).
 * 화면은 sendError 로 Boot 기본 /error → templates/error/403.html 이 그린다(참조 번호 포함).
 */
@Slf4j
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityEventRecorder events;

    public CustomAccessDeniedHandler(SecurityEventRecorder events) {
        this.events = events;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        boolean csrf = e instanceof CsrfException;
        SecurityEvent.Builder event = SecurityEvent.of(csrf ? SecurityEventType.CSRF_REJECTED : SecurityEventType.ACCESS_DENIED, EventOutcome.DENIED)
                .reasonCode(csrf ? "CSRF_TOKEN_INVALID" : "INSUFFICIENT_AUTHORITY")
                .request(request.getMethod(), request.getRequestURI());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthMember member) event.memberId(member.id());
        events.record(event);
        response.sendError(HttpStatus.FORBIDDEN.value());
    }
}
