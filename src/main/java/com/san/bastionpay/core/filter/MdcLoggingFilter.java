package com.san.bastionpay.core.filter;

import com.san.bastionpay.audit.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security 필터 체인보다 먼저 실행되어 보안 이벤트 로그에도 traceId, clientIp가 붙게 한다.
 * traceId는 서버에서만 생성한다(클라이언트 헤더 미신뢰). clientIp는 실제 소켓 주소이고, 신뢰 프록시로 설정된 주소에서 온 요청일 때만
 * X-Forwarded-For 를 반영한다(ClientIpResolver).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final ClientIpResolver clientIps;

    public MdcLoggingFilter(ClientIpResolver clientIps) {
        this.clientIps = clientIps;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put("traceId", traceId);
        MDC.put("clientIp", clientIps.resolve(request));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("clientIp");
        }
    }
}
