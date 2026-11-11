package com.san.mockbank.oauth;

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


/** access token. customerId 가 null 이면 이용기관 토큰(2-legged)이다. 원문은 저장하지 않는다. */
@Entity
@Table(name = "access_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "client_pk", nullable = false, updatable = false)
    private Long clientPk;

    @Column(name = "customer_id", updatable = false)
    private Long customerId;

    @Column(nullable = false, updatable = false, length = 100)
    private String scope;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AccessToken(String tokenHash, Long clientPk, Long customerId, String scope, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.clientPk = clientPk;
        this.customerId = customerId;
        this.scope = scope;
        this.expiresAt = expiresAt;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}
