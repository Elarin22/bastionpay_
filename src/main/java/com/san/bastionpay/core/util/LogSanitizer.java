package com.san.bastionpay.core.util;

/** 요청 경로처럼 공격자가 정할 수 있는 값을 로그에 남길 때 한 줄이 깨지지 않게 정리한다. */
public final class LogSanitizer {

    private static final int MAX_LENGTH = 200;

    private LogSanitizer() {
    }

    public static String clean(String value) {
        if (value == null) return "";
        String s = value.replaceAll("[\\p{Cntrl}]", "_");
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) + "..." : s;
    }
}
