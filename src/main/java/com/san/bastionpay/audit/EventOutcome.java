package com.san.bastionpay.audit;

/** 이벤트의 결과. */
public enum EventOutcome {
    SUCCESS,
    /** 시도가 실패했다(비밀번호 불일치, 이체 실패 등). */
    FAILURE,
    /** 정책이나 권한이 거부했다(인증 필요, 권한 없음, CSRF, 잠긴 계정 등). */
    DENIED,
    /** 사람이 확인해야 한다. */
    REVIEW
}
