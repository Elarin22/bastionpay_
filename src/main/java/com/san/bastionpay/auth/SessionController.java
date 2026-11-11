package com.san.bastionpay.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * 로그인 유지 시간 확인과 연장. 헤더의 타이머가 사용한다.
 * 남은 시간 조회는 활동으로 치지 않는다(필터가 /session/status 는 연장하지 않는다). 연장은 POST + CSRF 토큰이 필요하다.
 */
@Controller
public class SessionController {

    private final LoginSessionService sessions;

    public SessionController(LoginSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/session/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Object remaining = request.getAttribute(JwtAuthenticationFilter.SESSION_REMAINING);
        return noStore(remaining instanceof Long seconds ? seconds : 0L);
    }

    /** "시간 연장" 버튼: 남은 시간을 처음 값(30분)으로 되돌린다. */
    @PostMapping("/session/extend")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extend(HttpServletRequest request) {
        Object sid = request.getAttribute(JwtAuthenticationFilter.SESSION_ID);
        if (!(sid instanceof String sessionId)) return ResponseEntity.status(401).build();
        LoginSessionService.Status status = sessions.touch(sessionId, true);
        if (status.state() != LoginSessionService.State.ACTIVE) return ResponseEntity.status(401).build();
        return noStore(status.remainingSeconds());
    }

    private static ResponseEntity<Map<String, Object>> noStore(long remainingSeconds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("remainingSeconds", remainingSeconds));
    }
}
