package com.san.bastionpay.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면에서 헤더가 로그인 상태를 알 수 있게 한다. */
@ControllerAdvice
public class CurrentMemberAdvice {

    @ModelAttribute("currentMember")
    public AuthMember currentMember() {
        return resolve();
    }

    /** 헤더의 로그인 유지 타이머에 쓰는 남은 시간(초). 세션 정보가 없는 요청이면 null 이라 타이머를 보여 주지 않는다. */
    @ModelAttribute("sessionRemaining")
    public Long sessionRemaining() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object value = attributes.getAttribute(JwtAuthenticationFilter.SESSION_REMAINING,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return value instanceof Long seconds ? seconds : null;
    }

    /** 예외 처리 뷰처럼 @ModelAttribute 가 적용되지 않는 곳에서도 쓴다. */
    public static AuthMember resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthMember member) {
            return member;
        }
        return null;
    }
}
