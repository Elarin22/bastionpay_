package com.san.bastionpay.core.exception;

/**
 * 도메인 예외는 이 클래스 하나로 통일한다. 상태·문구는 ErrorCode 가 정한다.
 * detail 은 서버 로그에만 남고 화면에는 나가지 않는다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.code());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
