package com.san.mockbank.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 이체 요청 한 건. (이용기관, 거래고유번호)가 유일해 같은 요청을 두 번 처리하지 않는다. */
@Entity
@Table(name = "bank_transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_pk", nullable = false, updatable = false)
    private Long clientPk;

    @Column(name = "bank_tran_id", nullable = false, updatable = false, length = 20)
    private String bankTranId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private TransferKind kind;

    @Column(name = "customer_id", updatable = false)
    private Long customerId;

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(name = "total_amt", nullable = false, updatable = false)
    private long totalAmt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "rsp_code", nullable = false, length = 5, columnDefinition = "char(5)")
    private String rspCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public BankTransfer(Long clientPk, String bankTranId, TransferKind kind, Long customerId, int itemCount,
                        long totalAmt, TransferStatus status, String rspCode) {
        this.clientPk = clientPk;
        this.bankTranId = bankTranId;
        this.kind = kind;
        this.customerId = customerId;
        this.itemCount = itemCount;
        this.totalAmt = totalAmt;
        this.status = status;
        this.rspCode = rspCode;
        this.createdAt = LocalDateTime.now();
    }

    public void complete(TransferStatus status, String rspCode) {
        this.status = status;
        this.rspCode = rspCode;
    }
}
