package com.san.bastionpay.signup;

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

/**
 * 회원가입 진행 상태. 단계가 끝날 때마다 잠금 시각이 채워지며,
 * 잠긴 값(이메일·비밀번호)은 이 엔티티의 메서드로 다시 바꿀 수 없다.
 */
@Entity
@Table(name = "signup_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignupRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false, length = 254)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "code_expires_at", nullable = false)
    private LocalDateTime codeExpiresAt;

    @Column(name = "code_attempts", nullable = false)
    private int codeAttempts;

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(length = 50)
    private String name;

    @Column(length = 30)
    private String nickname;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "password_confirmed_at")
    private LocalDateTime passwordConfirmedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public SignupRequest(String tokenHash, String email, String codeHash,
                         LocalDateTime codeExpiresAt, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        this.tokenHash = tokenHash;
        this.email = email;
        this.codeHash = codeHash;
        this.codeExpiresAt = codeExpiresAt;
        this.lastSentAt = now;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean hasProfile() {
        return name != null && nickname != null;
    }

    public boolean isPasswordConfirmed() {
        return passwordConfirmedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }

    public SignupStep step() {
        if (!isEmailVerified()) return SignupStep.VERIFY_EMAIL;
        if (!hasProfile()) return SignupStep.PROFILE;
        if (!isPasswordConfirmed()) return SignupStep.PASSWORD;
        return SignupStep.REVIEW;
    }

    /** 새 코드로 교체한다. 시도 횟수는 초기화된다. */
    public void renewCode(String codeHash, LocalDateTime codeExpiresAt) {
        this.codeHash = codeHash;
        this.codeExpiresAt = codeExpiresAt;
        this.codeAttempts = 0;
        this.lastSentAt = LocalDateTime.now();
    }

    public void markEmailVerified() {
        this.emailVerifiedAt = LocalDateTime.now();
    }

    public void changeProfile(String name, String nickname) {
        this.name = name;
        this.nickname = nickname;
    }

    public void confirmPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordConfirmedAt = LocalDateTime.now();
    }
}
