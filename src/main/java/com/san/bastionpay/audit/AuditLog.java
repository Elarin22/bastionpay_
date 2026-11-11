package com.san.bastionpay.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 감사 기록 한 건(audit_log). 쓰고 나면 고치지 않는다. 시각은 UTC. */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    /** SYSTEM: 앱이 자동으로, ADMIN: 관리자가 화면에서, API: 서버 간 호출(n8n 등)이 API 키로. */
    public enum ActorType { SYSTEM, ADMIN, API }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 10)
    private ActorType actorType;

    @Column(name = "actor_member_id", updatable = false)
    private Long actorMemberId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private SecurityEventType action;

    @Column(name = "target_member_id", updatable = false)
    private Long targetMemberId;

    @Column(name = "reason_code", updatable = false, length = 40)
    private String reasonCode;

    @Column(name = "source_ip", updatable = false, length = 45)
    private String sourceIp;

    @Column(name = "trace_id", updatable = false, length = 40)
    private String traceId;

    @Column(name = "event_id", updatable = false, length = 36, columnDefinition = "char(36)")
    private String eventId;

    /** 보안 이벤트와 같은 event_id·시각·IP·추적 ID 로 남긴다. */
    public AuditLog(SecurityEvent event, ActorType actorType, Long actorMemberId, Long targetMemberId) {
        this.occurredAt = LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC);
        this.actorType = actorType;
        this.actorMemberId = actorMemberId;
        this.action = event.type();
        this.targetMemberId = targetMemberId;
        this.reasonCode = event.reasonCode();
        this.sourceIp = event.sourceIp();
        this.traceId = event.traceId();
        this.eventId = event.eventId();
    }
}
