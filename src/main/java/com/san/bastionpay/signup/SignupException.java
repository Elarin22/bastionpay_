package com.san.bastionpay.signup;

/** 사용자에게 그대로 보여 줘도 되는 가입 절차 오류. */
public class SignupException extends RuntimeException {

    public SignupException(String message) {
        super(message);
    }
}
