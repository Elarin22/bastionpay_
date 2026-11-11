package com.san.bastionpay.audit;

/** 로그에 남기는 계좌 식별자. 계좌번호 원문 대신 핀테크이용번호(가상 식별자)의 뒷부분만 쓴다. */
public final class AccountRefs {

    private AccountRefs() {
    }

    /** 예: 199900000000000000000102 -> fin-***000102 */
    public static String fromFintechUseNum(String fintechUseNum) {
        if (fintechUseNum == null || fintechUseNum.length() < 6) return null;
        return "fin-***" + fintechUseNum.substring(fintechUseNum.length() - 6);
    }
}
