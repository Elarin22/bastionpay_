package com.san.bastionpay.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** security_event 테이블 한 행. occurred_at 은 UTC 로 저장한다. */
@Entity
@Table(name = "security_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecurityEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 36, columnDefinition = "char(36)")
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private SecurityEventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "trace_id", updatable = false, length = 40)
    private String traceId;

    @Column(name = "source_ip", updatable = false, length = 45)
    private String sourceIp;

    @Column(name = "member_id", updatable = false)
    private Long memberId;

    @Column(name = "login_subject_hash", updatable = false, length = 64, columnDefinition = "char(64)")
    private String loginSubjectHash;

    @Column(name = "account_ref", updatable = false, length = 40)
    private String accountRef;

    @Column(name = "transfer_id", updatable = false)
    private Long transferId;

    @Column(updatable = false)
    private Long amount;

    @Column(updatable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private EventOutcome outcome;

    @Column(name = "reason_code", updatable = false, length = 40)
    private String reasonCode;

    @Column(name = "http_method", updatable = false, length = 10)
    private String httpMethod;

    @Column(name = "request_path", updatable = false, length = 200)
    private String requestPath;

    public SecurityEventEntity(SecurityEvent e) {
        this.eventId = e.eventId();
        this.eventType = e.type();
        this.occurredAt = LocalDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC);
        this.traceId = e.traceId();
        this.sourceIp = e.sourceIp();
        this.memberId = e.memberId();
        this.loginSubjectHash = e.loginSubjectHash();
        this.accountRef = e.accountRef();
        this.transferId = e.transferId();
        this.amount = e.amount();
        this.currency = e.currency();
        this.severity = e.severity();
        this.outcome = e.outcome();
        this.reasonCode = e.reasonCode();
        this.httpMethod = e.httpMethod();
        this.requestPath = e.requestPath();
    }
}
