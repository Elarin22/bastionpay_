package com.san.bastionpay.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 로그인 실패 누적과 임시 잠금 상태(account_lockout). 시각은 모두 UTC. */
@Entity
@Table(name = "account_lockout")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountLockout {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "window_started_at")
    private LocalDateTime windowStartedAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AccountLockout(Long memberId, LocalDateTime now) {
        this.memberId = memberId;
        this.updatedAt = now;
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 잠금 시간이 지났는데 아직 해제 처리가 안 된 상태. */
    public boolean isLockExpired(LocalDateTime now) {
        return lockedUntil != null && !lockedUntil.isAfter(now);
    }

    /** 실패 한 번을 센다. 시간창이 지났으면 새로 센다. 한도에 닿으면 true(잠금은 lock() 이 건다). */
    public boolean recordFailure(LocalDateTime now, java.time.Duration window, int maxFailures) {
        if (windowStartedAt == null || windowStartedAt.plus(window).isBefore(now)) {
            windowStartedAt = now;
            failedCount = 0;
        }
        failedCount++;
        updatedAt = now;
        return failedCount >= maxFailures;
    }

    public void lock(LocalDateTime now, java.time.Duration duration) {
        lockedAt = now;
        lockedUntil = now.plus(duration);
        updatedAt = now;
    }

    /** 잠금과 실패 횟수를 모두 되돌린다(성공 로그인·잠금 만료·관리자 해제). */
    public void reset(LocalDateTime now) {
        failedCount = 0;
        windowStartedAt = null;
        lockedAt = null;
        lockedUntil = null;
        updatedAt = now;
    }
}
