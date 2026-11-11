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

/** 이체 항목. 입금이체는 한 요청에 여러 항목이 있고, 출금이체는 항목이 하나다. */
@Entity
@Table(name = "bank_transfer_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankTransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private Long transferId;

    @Column(name = "tran_no", nullable = false, updatable = false)
    private int tranNo;

    @Column(name = "from_account_id", updatable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", updatable = false)
    private Long toAccountId;

    @Column(name = "tran_amt", nullable = false, updatable = false)
    private long tranAmt;

    @Column(name = "print_content", updatable = false, length = 100)
    private String printContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemStatus status;

    @Column(name = "rsp_code", nullable = false, length = 5, columnDefinition = "char(5)")
    private String rspCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public BankTransferItem(Long transferId, int tranNo, Long fromAccountId, Long toAccountId, long tranAmt,
                            String printContent, ItemStatus status, String rspCode) {
        this.transferId = transferId;
        this.tranNo = tranNo;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.tranAmt = tranAmt;
        this.printContent = printContent;
        this.status = status;
        this.rspCode = rspCode;
        this.createdAt = LocalDateTime.now();
    }

    public enum ItemStatus {
        COMPLETED, FAILED
    }
}
