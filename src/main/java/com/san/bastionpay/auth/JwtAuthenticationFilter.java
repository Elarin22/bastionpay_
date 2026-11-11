package com.san.bastionpay.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 쿠키의 Access Token 을 검증해 SecurityContext 를 채운다.
 * Access Token 이 없거나 만료됐고 Refresh Token 이 있으면 여기서 조용히 회전해 새 쿠키를 내려준다.
 * 빈으로 등록하지 않고 SecurityConfig 에서 직접 생성해 보안 필터 체인에만 넣는다(서블릿 필터 중복 등록 방지).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 같은 요청의 ERROR 디스패치에서 다시 회전하지 않도록 첫 번째 결과를 담아 둔다. */
    private static final String MEMBER_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".MEMBER";

    /** 이 요청의 로그인 세션 id 와 남은 시간(초). 헤더의 타이머와 /session/** 가 쓴다. */
    public static final String SESSION_ID = JwtAuthenticationFilter.class.getName() + ".SESSION_ID";
    public static final String SESSION_REMAINING = JwtAuthenticationFilter.class.getName() + ".SESSION_REMAINING";
    /** 활동이 없어 세션이 막 만료된 요청. 로그인 화면이 이유를 알려 주게 한다. */
    public static final String SESSION_EXPIRED = JwtAuthenticationFilter.class.getName() + ".SESSION_EXPIRED";

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final AuthCookies cookies;
    private final LoginSessionService sessions;

    public JwtAuthenticationFilter(JwtService jwtService, RefreshTokenService refreshTokens, AuthCookies cookies,
                                   LoginSessionService sessions) {
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.sessions = sessions;
    }

    /** 에러 페이지 렌더링에서도 로그인 상태(헤더)가 유지되도록 ERROR 디스패치에서도 동작한다. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthMember member = (AuthMember) request.getAttribute(MEMBER_ATTRIBUTE);
        if (member == null) {
            member = authenticate(request, response).orElse(null);
        }
        if (member != null) {
            request.setAttribute(MEMBER_ATTRIBUTE, member);
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    member, null, member.authorities());
            SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.getContextHolderStrategy().setContext(context);
        }
        chain.doFilter(request, response);
    }

    private Optional<AuthMember> authenticate(HttpServletRequest request, HttpServletResponse response) {
        boolean isStatic = isStaticResource(request);
        String access = AuthCookies.read(request, AuthCookies.ACCESS_TOKEN);
        if (access != null) {
            Optional<JwtService.Parsed> parsed = jwtService.parseSession(access);
            if (parsed.isPresent() && parsed.get().sessionId() != null) {
                // 정적 파일은 DB 를 보지 않는다(세션 확인·연장은 화면·API 요청에서만)
                if (isStatic) return Optional.of(parsed.get().member());
                LoginSessionService.Status status = checkSession(request, parsed.get().sessionId());
                if (status.state() == LoginSessionService.State.ACTIVE) {
                    markSession(request, parsed.get().sessionId(), status);
                    return Optional.of(parsed.get().member());
                }
                endedSession(request, response, parsed.get().sessionId(), status);
                return Optional.empty();
            }
            // sid 가 없는 예전 토큰은 세션을 알 수 없으므로 Refresh Token 으로 새로 받게 한다
        }
        String refresh = AuthCookies.read(request, AuthCookies.REFRESH_TOKEN);
        if (refresh == null || !canRefresh(request)) {
            return Optional.empty();
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refresh);
        switch (rotation.outcome()) {
            case ROTATED -> {
                String sid = rotation.issued().familyId();
                LoginSessionService.Status status = sessions.touch(sid, false);
                if (status.state() != LoginSessionService.State.ACTIVE) {
                    // 회전은 됐지만 이 로그인의 세션이 이미 끝났다(활동 없음·로그아웃). 새 토큰을 내려주지 않는다
                    refreshTokens.revokeFamily(sid);
                    endedSession(request, response, sid, status);
                    return Optional.empty();
                }
                cookies.writeAccessToken(response, jwtService.issueAccessToken(rotation.member(), sid));
                cookies.writeRefreshToken(response, rotation.issued());
                markSession(request, sid, status);
                return Optional.of(AuthMember.from(rotation.member()));
            }
            case INVALID -> {
                // 못 쓰는 토큰이 계속 전송되지 않도록 지운다
                cookies.clearAccessToken(response);
                cookies.clearRefreshToken(response);
            }
            case RACE -> { /* 다른 요청이 방금 새 쿠키를 내려줬을 수 있으므로 건드리지 않는다 */ }
        }
        return Optional.empty();
    }

    /** 남은 시간을 묻는 요청(/session/status)은 활동으로 치지 않는다. */
    private LoginSessionService.Status checkSession(HttpServletRequest request, String sid) {
        return request.getRequestURI().equals("/session/status") ? sessions.peek(sid) : sessions.touch(sid, false);
    }

    private static void markSession(HttpServletRequest request, String sid, LoginSessionService.Status status) {
        request.setAttribute(SESSION_ID, sid);
        request.setAttribute(SESSION_REMAINING, status.remainingSeconds());
    }

    /** 세션이 끝났다: 쿠키를 지우고, 활동 없음으로 만료된 경우에는 이벤트를 남기고 로그인 화면에 이유를 알린다. */
    private void endedSession(HttpServletRequest request, HttpServletResponse response, String sid, LoginSessionService.Status status) {
        cookies.clearAccessToken(response);
        cookies.clearRefreshToken(response);
        if (status.state() == LoginSessionService.State.EXPIRED) {
            sessions.expire(sid, status.memberId());
            refreshTokens.revokeFamily(sid);
            request.setAttribute(SESSION_EXPIRED, Boolean.TRUE);
        }
    }

    private static boolean isStaticResource(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/")
                || path.startsWith("/fonts/") || path.equals("/favicon.ico");
    }

    /**
     * 정적 리소스·로그아웃 요청에서는 회전하지 않는다. 한 화면이 CSS·JS 를 병렬로 받을 때
     * 같은 토큰이 여러 번 쓰이는 것을 줄이고, 로그아웃이 새 토큰을 만들지 않게 한다.
     */
    private static boolean canRefresh(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/")
                || path.startsWith("/fonts/") || path.equals("/favicon.ico")
                || path.startsWith("/actuator") || path.equals("/logout"));
    }
}
