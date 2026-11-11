package com.san.mockbank.api;

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


/** (이용기관, 거래고유번호)가 유일하다. 같은 번호로 다시 호출하면 중복 요청으로 거절하는 데 쓴다. */
@Entity
@Table(name = "api_call_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_pk", nullable = false, updatable = false)
    private Long clientPk;

    @Column(name = "api_name", nullable = false, updatable = false, length = 50)
    private String apiName;

    @Column(name = "bank_tran_id", nullable = false, updatable = false, length = 20)
    private String bankTranId;

    @Column(name = "customer_id", updatable = false)
    private Long customerId;

    @Column(name = "rsp_code", nullable = false, length = 5, columnDefinition = "char(5)")
    private String rspCode;

    @Column(name = "called_at", nullable = false, updatable = false)
    private LocalDateTime calledAt;

    public ApiCallLog(Long clientPk, String apiName, String bankTranId, Long customerId, String rspCode) {
        this.clientPk = clientPk;
        this.apiName = apiName;
        this.bankTranId = bankTranId;
        this.customerId = customerId;
        this.rspCode = rspCode;
        this.calledAt = LocalDateTime.now();
    }
}
