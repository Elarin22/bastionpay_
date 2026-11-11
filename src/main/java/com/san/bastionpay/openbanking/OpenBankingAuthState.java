package com.san.bastionpay.openbanking;

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

@Entity
@Table(name = "openbanking_auth_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpenBankingAuthState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", nullable = false, updatable = false, length = 64)
    private String stateHash;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public OpenBankingAuthState(String stateHash, Long memberId, LocalDateTime expiresAt) {
        this.stateHash = stateHash;
        this.memberId = memberId;
        this.expiresAt = expiresAt;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}
