package com.san.mockbank.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


/** 은행 계좌. 잔액은 @Version 으로 동시 변경을 막는다. */
@Entity
@Table(name = "bank_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "fintech_use_num", nullable = false, updatable = false, length = 24, columnDefinition = "char(24)")
    private String fintechUseNum;

    @Column(name = "bank_code_std", nullable = false, updatable = false, length = 3, columnDefinition = "char(3)")
    private String bankCodeStd;

    @Column(name = "bank_name", nullable = false, length = 30)
    private String bankName;

    @Column(name = "account_num", nullable = false, updatable = false, length = 20)
    private String accountNum;

    @Column(name = "account_num_masked", nullable = false, length = 20)
    private String accountNumMasked;

    @Column(name = "account_alias", nullable = false, length = 50)
    private String accountAlias;

    @Column(name = "account_holder_name", nullable = false, length = 50)
    private String accountHolderName;

    @Column(name = "account_type", nullable = false, length = 1, columnDefinition = "char(1)")
    private String accountType = "1";

    @Column(name = "balance_amt", nullable = false)
    private long balanceAmt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public BankAccount(Long customerId, String fintechUseNum, String bankCodeStd, String bankName, String accountNum,
                       String accountNumMasked, String accountAlias, String accountHolderName, long balanceAmt) {
        this.customerId = customerId;
        this.fintechUseNum = fintechUseNum;
        this.bankCodeStd = bankCodeStd;
        this.bankName = bankName;
        this.accountNum = accountNum;
        this.accountNumMasked = accountNumMasked;
        this.accountAlias = accountAlias;
        this.accountHolderName = accountHolderName;
        this.balanceAmt = balanceAmt;
        this.createdAt = LocalDateTime.now();
    }

    /** 출금. 잔액이 모자라면 거절한다(서비스가 먼저 확인하지만 여기서도 막는다). */
    public void withdraw(long amount) {
        if (amount <= 0 || amount > balanceAmt) throw new IllegalStateException("출금할 수 없는 금액");
        this.balanceAmt -= amount;
    }

    public void deposit(long amount) {
        if (amount <= 0) throw new IllegalStateException("입금할 수 없는 금액");
        this.balanceAmt += amount;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
