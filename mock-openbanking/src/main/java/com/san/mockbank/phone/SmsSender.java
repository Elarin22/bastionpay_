package com.san.mockbank.phone;

/** 문자 발송. 개발에서는 콘솔 구현이, 운영 설정(SOLAPI_ENABLED=true)에서는 솔라피 구현이 쓰인다. */
public interface SmsSender {

    /** @param to 숫자만 있는 휴대폰 번호 */
    void send(String to, String text);

    /** 실제로 문자를 보내지 않고 인증번호를 개발자에게 보여 주는 구현이면 true. */
    default boolean revealsCode() {
        return false;
    }
}
