package com.san.mockbank.oauth;

import org.springframework.http.HttpStatus;

/** 토큰 엔드포인트의 OAuth 오류(error 코드와 상태). */
public class OauthException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    public OauthException(String error, HttpStatus status, String detail) {
        super(detail);
        this.error = error;
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
