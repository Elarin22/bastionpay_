package com.san.bastionpay.transfer;

/**
 * 이체 진행 상태.
 * PENDING → WITHDRAWN(내 계좌에서 출금 완료) → COMPLETED(받는 계좌에 입금 완료)
 * 출금 거절은 FAILED, 입금이 거절되어 출금액을 돌려주면 REFUNDED,
 * 결과를 알 수 없거나 환불까지 실패하면 NEEDS_REVIEW(사람이 확인해야 한다).
 */
public enum TransferStatus {
    PENDING, WITHDRAWN, COMPLETED, FAILED, REFUNDED, NEEDS_REVIEW;

    /** 더 이상 자동으로 바뀌지 않는 상태. */
    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == REFUNDED || this == NEEDS_REVIEW;
    }
}
