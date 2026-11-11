package com.san.bastionpay.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 로그인 한 번에 해당하는 세션(login_session). 시각은 UTC. */
@Entity
@Table(name = "login_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginSession {

    @Id
    @Column(name = "session_id", length = 36, columnDefinition = "char(36)")
    private String sessionId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "idle_expires_at", nullable = false)
    private LocalDateTime idleExpiresAt;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "end_reason", length = 20)
    private String endReason;

    public LoginSession(String sessionId, Long memberId, LocalDateTime now, LocalDateTime idleExpiresAt) {
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.createdAt = now;
        this.lastActivityAt = now;
        this.idleExpiresAt = idleExpiresAt;
    }
}
