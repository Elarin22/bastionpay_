package com.san.mockbank.oauth;

/** 인증 화면에서 되돌려 보낼 수 없는 잘못된 요청(client_id·redirect URI 오류 등). 사용자에게 오류 화면을 보여 준다. */
public class AuthorizeException extends RuntimeException {

    public AuthorizeException(String message) {
        super(message);
    }
}
