package com.san.bastionpay.signup;

/** 이미 가입된 이메일로 가입을 시작했다. 컨트롤러가 가입 화면 대신 로그인 화면으로 보낸다. */
public class AlreadyRegisteredException extends SignupException {

    public AlreadyRegisteredException() {
        super("이미 가입된 이메일입니다. 로그인해 주세요.");
    }
}
