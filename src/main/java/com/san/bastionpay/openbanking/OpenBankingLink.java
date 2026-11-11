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

/** 회원의 오픈뱅킹 연결. access token 은 암호화된 값만 가진다. */
@Entity
@Table(name = "openbanking_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpenBankingLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "user_seq_no", nullable = false, length = 20)
    private String userSeqNo;

    @Column(name = "access_token_enc", nullable = false, length = 2048)
    private String accessTokenEnc;

    @Column(nullable = false, length = 100)
    private String scope;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public OpenBankingLink(Long memberId, String userSeqNo, String accessTokenEnc, String scope, LocalDateTime expiresAt) {
        this.memberId = memberId;
        this.createdAt = LocalDateTime.now();
        renew(userSeqNo, accessTokenEnc, scope, expiresAt);
    }

    /** 다시 연결하면 같은 행을 새 토큰으로 갱신한다. */
    public void renew(String userSeqNo, String accessTokenEnc, String scope, LocalDateTime expiresAt) {
        this.userSeqNo = userSeqNo;
        this.accessTokenEnc = accessTokenEnc;
        this.scope = scope;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}
