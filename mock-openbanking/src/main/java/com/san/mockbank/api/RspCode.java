package com.san.mockbank.api;

import org.springframework.http.HttpStatus;

/**
 * 이 가짜 서버가 정의한 응답 코드. 금융결제원의 실제 코드표를 확인하지 못해 코드 값은 임의로 정했다.
 * 토큰 문제는 HTTP 오류로, 요청 내용 문제는 HTTP 200 과 rsp_code 로 알린다(실제 오픈뱅킹 방식을 따름).
 */
public enum RspCode {

    SUCCESS("A0000", HttpStatus.OK, ""),
    INVALID_PARAMETER("A0001", HttpStatus.OK, "요청 파라미터가 올바르지 않습니다."),
    DUPLICATE_TRAN_ID("A0002", HttpStatus.OK, "이미 사용한 거래고유번호입니다."),
    INVALID_TOKEN("A0003", HttpStatus.UNAUTHORIZED, "토큰이 없거나 유효하지 않습니다."),
    INSUFFICIENT_SCOPE("A0004", HttpStatus.FORBIDDEN, "이 API 를 사용할 권한 범위(scope)가 없습니다."),
    ACCOUNT_NOT_AVAILABLE("A0005", HttpStatus.OK, "조회할 수 없는 계좌입니다."),
    USER_MISMATCH("A0006", HttpStatus.OK, "사용자 정보가 일치하지 않습니다."),
    PARTIAL_SUCCESS("A0007", HttpStatus.OK, "일부 이체만 처리되었습니다."),
    TRANSFER_NOT_PERMITTED("A0008", HttpStatus.OK, "이체가 허용되지 않은 계좌입니다."),
    INVALID_PASS_PHRASE("A0009", HttpStatus.OK, "입금이체 암호문구가 올바르지 않습니다."),
    INSUFFICIENT_BALANCE("A0010", HttpStatus.OK, "잔액이 부족합니다."),
    LIMIT_EXCEEDED("A0011", HttpStatus.OK, "이체 한도를 초과했습니다."),
    TRANSFER_FAILED("A0012", HttpStatus.OK, "이체를 처리하지 못했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;

    RspCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
