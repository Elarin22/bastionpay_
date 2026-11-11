package com.san.bastionpay.admin;

import com.san.bastionpay.audit.Severity;
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

/**
 * 보안 인시던트 티켓(incident). 탐지·대응 뒤에 "무슨 일이 언제, 무엇을 했나"를 한 건으로 남긴다.
 * 같은 출발지 IP 의 열린 티켓은 하나뿐이고, 사건이 더 쌓이면 그 티켓을 갱신한다(DB 가 보장). 시각은 UTC.
 */
@Entity
@Table(name = "incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident {

    public enum Status { OPEN, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "src_ip", nullable = false, updatable = false, length = 45)
    private String srcIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(length = 255)
    private String actions;

    @Column(name = "created_by", nullable = false, updatable = false, length = 50)
    private String createdBy;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public Incident(String srcIp, String createdBy, LocalDateTime nowUtc) {
        this.srcIp = srcIp;
        this.createdBy = createdBy;
        this.status = Status.OPEN;
        this.createdAt = nowUtc;
        this.updatedAt = nowUtc;
    }

    /** 열린 티켓을 새 내용으로 갱신한다. 심각도는 내려가지 않는다(Critical 티켓에 나중에 Medium 경보가 합쳐져도 유지). */
    public void refresh(String title, Severity severity, String summary, int eventCount, String actions, LocalDateTime nowUtc) {
        this.title = title;
        this.severity = this.severity == null || severity.compareTo(this.severity) > 0 ? severity : this.severity;
        this.summary = summary;
        this.eventCount = eventCount;
        this.actions = actions;
        this.updatedAt = nowUtc;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public void close(String by, LocalDateTime nowUtc) {
        this.status = Status.CLOSED;
        this.closedBy = by;
        this.closedAt = nowUtc;
        this.updatedAt = nowUtc;
    }
}
