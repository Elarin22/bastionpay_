package com.san.bastionpay.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 요청의 출발지 IP. 기본은 소켓의 실제 주소(getRemoteAddr)다.
 * 신뢰하도록 설정한 프록시에서 온 요청일 때만 X-Forwarded-For 를 읽는데, 오른쪽(가까운 쪽)부터 신뢰 프록시를 건너뛰고
 * 처음 나오는 주소를 쓴다. 클라이언트가 왼쪽에 끼워 넣은 값은 쓰지 않는다. IP 형식이 아닌 값은 버린다.
 */
@Component
public class ClientIpResolver {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final SecurityProperties props;

    public ClientIpResolver(SecurityProperties props) {
        this.props = props;
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || !props.trustedProxies().contains(remote)) return remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remote;
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (props.trustedProxies().contains(hop)) continue;
            return isIp(hop) ? hop : remote;
        }
        return remote;
    }

    public static boolean isIp(String s) {
        if (s == null || s.length() > 45) return false;
        if (IPV4.matcher(s).matches()) {
            for (String part : s.split("\\.")) if (Integer.parseInt(part) > 255) return false;
            return true;
        }
        return s.contains(":") && IPV6.matcher(s).matches();
    }
}
