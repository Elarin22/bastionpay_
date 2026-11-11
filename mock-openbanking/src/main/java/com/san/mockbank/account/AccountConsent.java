package com.san.mockbank.account;

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

/** 고객이 이용기관에 허용한 계좌. 허용 범위(조회/이체)를 따로 가진다. */
@Entity
@Table(name = "account_consent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_pk", nullable = false, updatable = false)
    private Long clientPk;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "inquiry_agree", nullable = false)
    private boolean inquiryAgree;

    @Column(name = "transfer_agree", nullable = false)
    private boolean transferAgree;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public AccountConsent(Long clientPk, Long accountId, boolean inquiryAgree, boolean transferAgree) {
        this.clientPk = clientPk;
        this.accountId = accountId;
        this.inquiryAgree = inquiryAgree;
        this.transferAgree = transferAgree;
        this.agreedAt = LocalDateTime.now();
    }

    /** 다시 인증해서 허용하면 같은 행을 갱신한다(철회 상태도 풀린다). */
    public void renew(boolean inquiryAgree, boolean transferAgree) {
        this.inquiryAgree = inquiryAgree;
        this.transferAgree = transferAgree;
        this.agreedAt = LocalDateTime.now();
        this.revokedAt = null;
    }

    public void revoke() {
        if (revokedAt == null) this.revokedAt = LocalDateTime.now();
    }

    public boolean canInquire() {
        return revokedAt == null && inquiryAgree;
    }

    public boolean canTransfer() {
        return revokedAt == null && transferAgree;
    }
}
