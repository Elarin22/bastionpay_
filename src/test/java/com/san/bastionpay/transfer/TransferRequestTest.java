package com.san.bastionpay.transfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 이체 상태는 정해진 순서로만 바뀐다. */
class TransferRequestTest {

    private static TransferRequest request() {
        return new TransferRequest(1L, "11111111-1111-1111-1111-111111111111", "199900000000000000000002", "한빛은행 월급 통장",
                "901", "200-***-9012", 1_000, null);
    }

    @Test
    void happyPathGoesPendingWithdrawnCompleted() {
        TransferRequest r = request();
        assertThat(r.getStatus()).isEqualTo(TransferStatus.PENDING);

        r.startWithdraw("M202610001UAAAAAAAA1");
        r.withdrawn();
        r.startDeposit("M202610001UAAAAAAAA2");
        r.completed("브***피");

        assertThat(r.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(r.getToHolderMasked()).isEqualTo("브***피");
        assertThat(r.getWithdrawTranId()).isEqualTo("M202610001UAAAAAAAA1");
        assertThat(r.getDepositTranId()).isEqualTo("M202610001UAAAAAAAA2");
        assertThat(r.getStatus().isFinal()).isTrue();
    }

    @Test
    void refundPathKeepsDepositFailureCode() {
        TransferRequest r = request();
        r.startWithdraw("a");
        r.withdrawn();
        r.startDeposit("b");
        r.startRefund("c");
        r.refunded("A001");

        assertThat(r.getStatus()).isEqualTo(TransferStatus.REFUNDED);
        assertThat(r.getFailureCode()).isEqualTo("A001");
        assertThat(r.getRefundTranId()).isEqualTo("c");
    }

    @Test
    void stepsCannotBeSkippedOrRepeated() {
        TransferRequest r = request();

        assertThatThrownBy(() -> r.completed("x")).isInstanceOf(IllegalStateException.class);     // 출금 전에 완료할 수 없다
        assertThatThrownBy(() -> r.startDeposit("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.refunded("x")).isInstanceOf(IllegalStateException.class);      // 출금 전에 환불할 수 없다
        r.withdrawn();
        assertThatThrownBy(r::withdrawn).isInstanceOf(IllegalStateException.class);               // 두 번 출금 처리할 수 없다
        assertThatThrownBy(() -> r.failed("x")).isInstanceOf(IllegalStateException.class);        // 출금된 뒤에는 '출금 거절'이 될 수 없다
    }

    @Test
    void finalStatesAreFinal() {
        TransferRequest done = request();
        done.withdrawn();
        done.completed(null);
        TransferRequest failed = request();
        failed.failed("A002");
        TransferRequest review = request();
        review.needsReview("DEPOSIT_UNKNOWN");

        for (TransferRequest r : new TransferRequest[]{done, failed, review}) {
            assertThat(r.getStatus().isFinal()).isTrue();
            assertThatThrownBy(() -> r.needsReview("again")).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> failed.withdrawn()).isInstanceOf(IllegalStateException.class);
        assertThat(TransferStatus.PENDING.isFinal()).isFalse();
        assertThat(TransferStatus.WITHDRAWN.isFinal()).isFalse();
    }
}
