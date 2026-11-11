package com.san.bastionpay.transfer;

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

/** 이체 요청 한 건과 그 진행 상태. 상태는 정해진 순서로만 바뀐다. */
@Entity
@Table(name = "transfer_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 36, columnDefinition = "char(36)")
    private String requestId;

    @Column(name = "from_fintech_use_num", nullable = false, updatable = false, length = 24, columnDefinition = "char(24)")
    private String fromFintechUseNum;

    @Column(name = "from_label", nullable = false, updatable = false, length = 100)
    private String fromLabel;

    @Column(name = "to_bank_code", nullable = false, updatable = false, length = 3, columnDefinition = "char(3)")
    private String toBankCode;

    @Column(name = "to_account_masked", nullable = false, updatable = false, length = 30)
    private String toAccountMasked;

    @Column(name = "to_holder_masked", length = 50)
    private String toHolderMasked;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(updatable = false, length = 20)
    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "failure_code", length = 30)
    private String failureCode;

    @Column(name = "withdraw_tran_id", length = 20)
    private String withdrawTranId;

    @Column(name = "deposit_tran_id", length = 20)
    private String depositTranId;

    @Column(name = "refund_tran_id", length = 20)
    private String refundTranId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public TransferRequest(Long memberId, String requestId, String fromFintechUseNum, String fromLabel, String toBankCode,
                           String toAccountMasked, long amount, String memo) {
        this.memberId = memberId;
        this.requestId = requestId;
        this.fromFintechUseNum = fromFintechUseNum;
        this.fromLabel = fromLabel;
        this.toBankCode = toBankCode;
        this.toAccountMasked = toAccountMasked;
        this.amount = amount;
        this.memo = memo;
        this.status = TransferStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** 출금을 요청하기 전에 거래고유번호를 기록한다(요청 도중 장애가 나도 어떤 요청이었는지 알 수 있게). */
    public void startWithdraw(String tranId) {
        requireStatus(TransferStatus.PENDING);
        this.withdrawTranId = tranId;
        touch();
    }

    public void withdrawn() {
        requireStatus(TransferStatus.PENDING);
        change(TransferStatus.WITHDRAWN, null);
    }

    public void startDeposit(String tranId) {
        requireStatus(TransferStatus.WITHDRAWN);
        this.depositTranId = tranId;
        touch();
    }

    public void completed(String holderMasked) {
        requireStatus(TransferStatus.WITHDRAWN);
        this.toHolderMasked = holderMasked;
        change(TransferStatus.COMPLETED, null);
    }

    /** 출금이 확실히 거절됨(돈이 움직이지 않았다). */
    public void failed(String code) {
        requireStatus(TransferStatus.PENDING);
        change(TransferStatus.FAILED, code);
    }

    public void startRefund(String tranId) {
        requireStatus(TransferStatus.WITHDRAWN);
        this.refundTranId = tranId;
        touch();
    }

    /** 입금이 거절되어 출금액을 돌려줌. code 는 입금 거절 사유. */
    public void refunded(String code) {
        requireStatus(TransferStatus.WITHDRAWN);
        change(TransferStatus.REFUNDED, code);
    }

    /** 결과를 알 수 없거나 환불에 실패해 사람이 확인해야 한다. 어느 단계에서든 넘어갈 수 있다. */
    public void needsReview(String code) {
        if (status.isFinal()) throw new IllegalStateException("이미 끝난 이체 요청: " + status);
        change(TransferStatus.NEEDS_REVIEW, code);
    }

    private void requireStatus(TransferStatus expected) {
        if (status != expected) throw new IllegalStateException("이체 상태 " + status + " 에서는 할 수 없는 동작(필요: " + expected + ")");
    }

    private void change(TransferStatus next, String code) {
        this.status = next;
        this.failureCode = code;
        touch();
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
