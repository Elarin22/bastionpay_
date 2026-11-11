package com.san.mockbank.account;

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


@Entity
@Table(name = "bank_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "tran_at", nullable = false, updatable = false)
    private LocalDateTime tranAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "inout_type", nullable = false, updatable = false, length = 10)
    private InOutType inoutType;

    @Column(name = "tran_type", nullable = false, updatable = false, length = 20)
    private String tranType;

    @Column(name = "print_content", nullable = false, updatable = false, length = 100)
    private String printContent;

    @Column(name = "tran_amt", nullable = false, updatable = false)
    private long tranAmt;

    @Column(name = "after_balance_amt", nullable = false, updatable = false)
    private long afterBalanceAmt;

    @Column(name = "branch_name", updatable = false, length = 50)
    private String branchName;

    @Column(name = "bank_tran_id", updatable = false, length = 20)
    private String bankTranId;

    @Column(name = "transfer_item_id", updatable = false)
    private Long transferItemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public BankTransaction(Long accountId, LocalDateTime tranAt, InOutType inoutType, String tranType, String printContent,
                           long tranAmt, long afterBalanceAmt, String branchName, String bankTranId, Long transferItemId) {
        this.accountId = accountId;
        this.tranAt = tranAt;
        this.inoutType = inoutType;
        this.tranType = tranType;
        this.printContent = printContent;
        this.tranAmt = tranAmt;
        this.afterBalanceAmt = afterBalanceAmt;
        this.branchName = branchName;
        this.bankTranId = bankTranId;
        this.transferItemId = transferItemId;
        this.createdAt = LocalDateTime.now();
    }
}
