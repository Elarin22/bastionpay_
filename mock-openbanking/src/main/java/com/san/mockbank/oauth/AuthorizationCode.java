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


/** 인증 코드. 원문은 저장하지 않고 해시만 저장한다. 한 번 쓰면 used_at 이 채워진다. */
@Entity
@Table(name = "authorization_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthorizationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64, columnDefinition = "char(64)")
    private String codeHash;

    @Column(name = "client_pk", nullable = false, updatable = false)
    private Long clientPk;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "redirect_uri", nullable = false, updatable = false, length = 300)
    private String redirectUri;

    @Column(nullable = false, updatable = false, length = 100)
    private String scope;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AuthorizationCode(String codeHash, Long clientPk, Long customerId, String redirectUri, String scope,
                             LocalDateTime expiresAt) {
        this.codeHash = codeHash;
        this.clientPk = clientPk;
        this.customerId = customerId;
        this.redirectUri = redirectUri;
        this.scope = scope;
        this.expiresAt = expiresAt;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}
