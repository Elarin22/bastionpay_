package com.san.bastionpay.audit;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 이벤트를 두 곳에 남긴다: (1) 전용 로거 SECURITY_EVENT 가 JSON 한 줄(JSONL)로 로그 파일에 쓴다 — Graylog·Wazuh 수집용,
 * (2) security_event 테이블 — 보안 대시보드 집계용. 어느 쪽이 실패해도 요청을 실패시키지 않는다.
 */
@Slf4j
@Component
public class SecurityEventRecorder {

    /** logback-spring.xml 이 이 이름의 로거를 logs/security-events.jsonl 로 보낸다. */
    public static final String LOGGER_NAME = "SECURITY_EVENT";
    private static final Logger EVENT_LOG = LoggerFactory.getLogger(LOGGER_NAME);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecurityEventStore store;

    public SecurityEventRecorder(SecurityEventStore store) {
        this.store = store;
    }

    public SecurityEvent record(SecurityEvent.Builder builder) {
        return record(builder.build());
    }

    /** 기록한 이벤트(추적 ID·출발지 IP 가 채워진 것)를 돌려준다. 감사 기록이 같은 event_id 로 이어 쓰는 데 쓴다. */
    public SecurityEvent record(SecurityEvent event) {
        SecurityEvent e = complete(event);
        try {
            EVENT_LOG.info(JSON.writeValueAsString(e.toFields()));
        } catch (RuntimeException ex) {
            log.error("보안 이벤트 로그 기록 실패 type={}", e.type(), ex);
        }
        try {
            store.save(e);
        } catch (RuntimeException ex) {
            log.error("보안 이벤트 DB 저장 실패 type={} eventId={}", e.type(), e.eventId(), ex);
        }
        return e;
    }

    /** 요청 안에서 기록하면 trace_id·source_ip 를 현재 요청의 값으로 채운다(MdcLoggingFilter 가 넣어 둔 값). */
    private static SecurityEvent complete(SecurityEvent e) {
        String trace = e.traceId() != null ? e.traceId() : MDC.get("traceId");
        String ip = e.sourceIp() != null ? e.sourceIp() : MDC.get("clientIp");
        if (java.util.Objects.equals(trace, e.traceId()) && java.util.Objects.equals(ip, e.sourceIp())) return e;
        return new SecurityEvent(e.eventId(), e.type(), e.occurredAt(), trace, ip, e.memberId(), e.loginSubjectHash(),
                e.accountRef(), e.transferId(), e.amount(), e.currency(), e.severity(), e.outcome(), e.reasonCode(), e.httpMethod(), e.requestPath());
    }
}
