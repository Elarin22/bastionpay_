package com.san.mockbank.phone;

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

/** 문자로 보낸 인증번호 한 건. 원문은 저장하지 않는다. */
@Entity
@Table(name = "sms_verification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SmsVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 11)
    private String phone;

    @Column(name = "code_salt", nullable = false, updatable = false, length = 32, columnDefinition = "char(32)")
    private String codeSalt;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64, columnDefinition = "char(64)")
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public SmsVerification(String phone, String codeSalt, String codeHash, LocalDateTime expiresAt) {
        this.phone = phone;
        this.codeSalt = codeSalt;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void recordAttempt() {
        this.attempts++;
    }

    public void consume() {
        if (consumedAt == null) this.consumedAt = LocalDateTime.now();
    }
}
