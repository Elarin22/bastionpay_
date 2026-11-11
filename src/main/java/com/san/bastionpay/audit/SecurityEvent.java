package com.san.bastionpay.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 보안 이벤트 한 건. 해당되는 필드만 채운다(null 은 로그에 아예 나오지 않는다).
 * traceId·sourceIp 를 비워 두면 기록할 때 현재 요청의 값이 들어간다.
 */
public record SecurityEvent(
        String eventId,
        SecurityEventType type,
        Instant occurredAt,
        String traceId,
        String sourceIp,
        Long memberId,
        String loginSubjectHash,
        String accountRef,
        Long transferId,
        Long amount,
        String currency,
        Severity severity,
        EventOutcome outcome,
        String reasonCode,
        String httpMethod,
        String requestPath) {

    public static Builder of(SecurityEventType type, EventOutcome outcome) {
        return new Builder(type, outcome);
    }

    /** JSON 한 줄로 만들 때 쓰는 필드 이름·순서. 값이 없는 필드는 넣지 않는다. */
    public Map<String, Object> toFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_id", eventId);
        m.put("event_type", type.name());
        m.put("occurred_at", occurredAt.toString());
        put(m, "trace_id", traceId);
        put(m, "source_ip", sourceIp);
        put(m, "member_id", memberId);
        put(m, "login_subject_hash", loginSubjectHash);
        put(m, "account_ref", accountRef);
        put(m, "transfer_id", transferId);
        put(m, "amount", amount);
        put(m, "currency", currency);
        m.put("severity", severity.name());
        m.put("outcome", outcome.name());
        put(m, "reason_code", reasonCode);
        put(m, "http_method", httpMethod);
        put(m, "request_path", requestPath);
        return m;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }

    public static final class Builder {
        private final SecurityEventType type;
        private final EventOutcome outcome;
        private Severity severity;
        private String traceId;
        private String sourceIp;
        private Long memberId;
        private String loginSubjectHash;
        private String accountRef;
        private Long transferId;
        private Long amount;
        private String currency;
        private String reasonCode;
        private String httpMethod;
        private String requestPath;

        private Builder(SecurityEventType type, EventOutcome outcome) {
            this.type = type;
            this.outcome = outcome;
        }

        public Builder severity(Severity v) { this.severity = v; return this; }
        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder sourceIp(String v) { this.sourceIp = v; return this; }
        public Builder memberId(Long v) { this.memberId = v; return this; }
        public Builder loginSubjectHash(String v) { this.loginSubjectHash = v; return this; }
        public Builder accountRef(String v) { this.accountRef = v; return this; }
        public Builder transferId(Long v) { this.transferId = v; return this; }
        public Builder reasonCode(String v) { this.reasonCode = v; return this; }

        /** 막힌 요청의 메서드와 경로. 경로는 쿼리를 뺀 값이고, 계좌번호처럼 긴 숫자는 가려서 남긴다. */
        public Builder request(String method, String path) {
            this.httpMethod = method;
            this.requestPath = RequestPaths.sanitize(path);
            return this;
        }

        /** 금액(원). 통화는 KRW 로 기록한다. */
        public Builder amountKrw(long v) {
            this.amount = v;
            this.currency = "KRW";
            return this;
        }

        public SecurityEvent build() {
            return new SecurityEvent(UUID.randomUUID().toString(), type, Instant.now(), traceId, sourceIp, memberId,
                    loginSubjectHash, accountRef, transferId, amount, currency,
                    severity != null ? severity : type.defaultSeverity(), outcome, reasonCode, httpMethod, requestPath);
        }
    }
}
