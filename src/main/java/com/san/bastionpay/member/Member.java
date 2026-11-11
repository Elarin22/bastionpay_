package com.san.bastionpay.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    /** 잠긴 시각(UTC)과 사유. status 가 LOCKED 일 때만 의미가 있다. */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "lock_reason", length = 200)
    private String lockReason;

    /** 마지막으로 앱을 이용한 시각. 오래 이용하지 않으면 등급을 한 단계씩 내린다(서버 지역 시간). */
    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt;

    /** 등급이 마지막으로 바뀐 시각(승급·강등). */
    @Column(name = "tier_changed_at")
    private LocalDateTime tierChangedAt;

    /** 누적 이체 금액을 세기 시작하는 시각. 가입 시각에서 시작하고 강등되면 그 시각으로 다시 시작한다. */
    @Column(name = "tier_baseline_at", nullable = false)
    private LocalDateTime tierBaselineAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Member(String email, String name, String nickname, String passwordHash) {
        this.email = email;
        this.name = name;
        this.nickname = nickname;
        this.passwordHash = passwordHash;
        this.role = MemberRole.USER;
        this.tier = MemberTier.STANDARD;
        this.status = MemberStatus.ACTIVE;
    }

    public void changeTier(MemberTier tier) {
        this.tier = tier;
    }

    /** 누적 이체 금액이 기준을 넘어 등급이 오른다. 누적은 계속 이어서 센다. */
    public void promoteTo(MemberTier tier, LocalDateTime now) {
        this.tier = tier;
        this.tierChangedAt = now;
    }

    /** 오래 이용하지 않아 등급이 내려간다. 누적 이체 금액은 이 시각부터 다시 센다. */
    public void demoteTo(MemberTier tier, LocalDateTime now) {
        this.tier = tier;
        this.tierChangedAt = now;
        this.tierBaselineAt = now;
    }

    public void recordActivity(LocalDateTime now) {
        this.lastActiveAt = now;
    }

    /** 관리자·자동화가 계정을 잠근다. 활성 계정만 잠글 수 있고, 로그인과 기존 세션은 호출하는 쪽이 끊는다. */
    public void lock(String reason, LocalDateTime nowUtc) {
        this.status = MemberStatus.LOCKED;
        this.lockedAt = nowUtc;
        this.lockReason = reason;
    }

    public void unlock() {
        this.status = MemberStatus.ACTIVE;
        this.lockedAt = null;
        this.lockReason = null;
    }

    /** 관리자 권한 회수(최소권한 복원): ADMIN -> USER. */
    public void revokeAdmin() {
        this.role = MemberRole.USER;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
        if (lastActiveAt == null) lastActiveAt = createdAt;
        if (tierBaselineAt == null) tierBaselineAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
