package com.san.mockbank.customer;

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
@Table(name = "bank_customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_seq_no", nullable = false, updatable = false, length = 10, columnDefinition = "char(10)")
    private String userSeqNo;

    @Column(name = "login_id", nullable = false, updatable = false, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    /** 문자 인증에 쓰는 휴대폰 번호(숫자만). */
    @Column(length = 11)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public BankCustomer(String userSeqNo, String loginId, String passwordHash, String name) {
        this.userSeqNo = userSeqNo;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.createdAt = LocalDateTime.now();
    }

    public void changePhone(String phone) {
        this.phone = phone;
    }

    public void changeStatus(CustomerStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }
}
