package com.san.mockbank.phone;

/** 휴대폰 인증 실패. message 는 사용자 화면에 그대로 나가는 문구다. 끝났으면(finished) 인증을 처음부터 다시 해야 한다. */
public class PhoneAuthException extends RuntimeException {

    private final boolean finished;

    public PhoneAuthException(String message, boolean finished) {
        super(message);
        this.finished = finished;
    }

    public boolean isFinished() {
        return finished;
    }
}
