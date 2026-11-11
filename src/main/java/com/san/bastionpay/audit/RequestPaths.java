package com.san.bastionpay.audit;

/** 보안 이벤트에 남기는 요청 경로 정리. 한 줄 로그가 깨지지 않게 하고, 경로에 섞인 긴 숫자(계좌·식별번호)는 가린다. */
final class RequestPaths {

    private static final int MAX_LENGTH = 200;

    private RequestPaths() {
    }

    static String sanitize(String path) {
        if (path == null) return null;
        String s = path.replaceAll("[\\p{Cntrl}]", "_").replaceAll("\\d{10,}", "***");
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }
}
