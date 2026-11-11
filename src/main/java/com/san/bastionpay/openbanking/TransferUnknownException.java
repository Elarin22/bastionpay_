package com.san.bastionpay.openbanking;

import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;

/**
 * 이체 요청을 보냈지만 결과를 알 수 없는 경우(시간 초과, 연결 끊김, 은행 서버 오류).
 * 돈이 움직였는지 모르므로 같은 요청을 다시 보내거나 환불하지 말고 사람이 확인해야 한다.
 */
public class TransferUnknownException extends BusinessException {

    public TransferUnknownException(String detail) {
        super(ErrorCode.TRANSFER_PENDING_REVIEW, detail);
    }
}
