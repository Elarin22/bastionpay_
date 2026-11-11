package com.san.bastionpay.core.exception;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** 예외 처리 검증용. 테스트 클래스패스에만 있다. */
@Controller
class ThrowingTestController {

    @GetMapping("/test-errors/business")
    void business() {
        throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "internal-detail-must-not-leak");
    }

    @GetMapping("/test-errors/boom")
    void boom() {
        throw new IllegalStateException("secret-internal-message");
    }

    @GetMapping("/test-errors/denied")
    void denied() {
        throw new AccessDeniedException("no");
    }

    @GetMapping("/test-errors/unauthenticated")
    void unauthenticated() {
        throw new BadCredentialsException("no");
    }

    @GetMapping("/test-errors/param")
    @ResponseBody
    String param(@RequestParam int n) {
        return "ok " + n;
    }
}
