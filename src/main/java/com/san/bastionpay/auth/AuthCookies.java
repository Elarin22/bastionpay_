package com.san.bastionpay.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class AuthCookies {

    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";

    private final JwtProperties props;

    public AuthCookies(JwtProperties props) {
        this.props = props;
    }

    public void writeAccessToken(HttpServletResponse response, String token) {
        add(response, build(ACCESS_TOKEN, token, "/", props.accessTokenTtl()));
    }

    public void clearAccessToken(HttpServletResponse response) {
        add(response, build(ACCESS_TOKEN, "", "/", Duration.ZERO));
    }

    /** 만료 시각은 로그인 시점에 고정이라, 회전된 토큰의 쿠키는 남은 시간만큼만 유지한다. */
    public void writeRefreshToken(HttpServletResponse response, RefreshTokenService.Issued issued) {
        Duration remaining = Duration.between(LocalDateTime.now(), issued.expiresAt());
        add(response, build(REFRESH_TOKEN, issued.token(), "/", remaining.isNegative() ? Duration.ZERO : remaining));
    }

    public void clearRefreshToken(HttpServletResponse response) {
        add(response, build(REFRESH_TOKEN, "", "/", Duration.ZERO));
    }

    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    /** 가입 진행 토큰 등 보조 쿠키도 같은 보안 속성으로 만든다. */
    public ResponseCookie build(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.cookie().secure())
                .sameSite(props.cookie().sameSite())
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    public void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
