package com.san.mockbank.api;

/** API 오류. 사용자에게는 RspCode 의 문구만 나가고 detail 은 서버 로그에만 남는다. */
public class ApiException extends RuntimeException {

    private final RspCode rspCode;

    public ApiException(RspCode rspCode) {
        this(rspCode, rspCode.code());
    }

    public ApiException(RspCode rspCode, String detail) {
        super(detail);
        this.rspCode = rspCode;
    }

    public RspCode getRspCode() {
        return rspCode;
    }
}
