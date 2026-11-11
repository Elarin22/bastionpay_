package com.san.bastionpay.openbanking;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * 은행 인증 후 돌아올 주소. 접속한 주소(localhost, 팀원이 쓰는 IP 등)에 맞는 값을 허용 목록에서 고른다.
 * 쿠키는 접속한 호스트에만 붙으므로, 시작한 주소와 돌아오는 주소가 같아야 state 쿠키가 따라온다.
 * 목록에 없는 주소로 접속하면 기본 주소를 쓴다(요청 값을 그대로 쓰지 않는다).
 */
@Component
public class RedirectUris {

    private final String fallback;
    private final List<String> allowed;

    public RedirectUris(OpenBankingProperties props, @Value("${app.openbanking.extra-redirect-uris:}") List<String> extra) {
        this.fallback = props.redirectUri();
        this.allowed = new java.util.ArrayList<>();
        this.allowed.add(props.redirectUri());
        extra.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(this.allowed::add);
    }

    /** 이 요청이 들어온 주소(스킴·호스트·포트)와 같은 허용 주소. 없으면 기본 주소. */
    public String forRequest(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        for (String candidate : allowed) {
            if (candidate == null) continue;
            URI uri;
            try {
                uri = URI.create(candidate);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (scheme.equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost())
                    && port == (uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort())) {
                return candidate;
            }
        }
        return fallback;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
