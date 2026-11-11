package com.san.bastionpay.audit;

/** 기록하는 보안 이벤트 종류. 이름은 로그·DB 의 event_type 값 그대로다(바꾸면 탐지 룰이 깨진다). */
public enum SecurityEventType {
    LOGIN_SUCCESS(Severity.INFO, Category.AUTH),
    LOGIN_FAILURE(Severity.LOW, Category.AUTH),
    AUTHENTICATION_REQUIRED(Severity.LOW, Category.ACCESS),
    ACCESS_DENIED(Severity.MEDIUM, Category.ACCESS),
    CSRF_REJECTED(Severity.MEDIUM, Category.ACCESS),
    TRANSFER_REQUESTED(Severity.INFO, Category.TRANSFER),
    TRANSFER_COMPLETED(Severity.INFO, Category.TRANSFER),
    TRANSFER_FAILED(Severity.LOW, Category.TRANSFER),
    TRANSFER_NEEDS_REVIEW(Severity.HIGH, Category.TRANSFER),
    TRANSFER_REFUNDED(Severity.MEDIUM, Category.TRANSFER),
    TRANSFER_REFUND_FAILED(Severity.CRITICAL, Category.TRANSFER),
    TRANSFER_REPLAY(Severity.LOW, Category.TRANSFER),
    // 위 12개는 연동 문서의 고정 목록이다. 아래는 앱이 이미 기록하던 이벤트다.
    REFRESH_TOKEN_REUSE(Severity.HIGH, Category.AUTH),
    SIGNUP_COMPLETE(Severity.INFO, Category.AUTH),
    OPENBANKING_LINKED(Severity.INFO, Category.OPENBANKING),
    OPENBANKING_UNLINKED(Severity.INFO, Category.OPENBANKING),
    OPENBANKING_LINK_REJECTED(Severity.MEDIUM, Category.OPENBANKING),
    SESSION_EXPIRED(Severity.INFO, Category.AUTH),
    TIER_CHANGED(Severity.INFO, Category.ACCOUNT),
    INCIDENT_CREATED(Severity.INFO, Category.RESPONSE),
    INCIDENT_CLOSED(Severity.INFO, Category.RESPONSE),
    IP_BLOCKED(Severity.HIGH, Category.RESPONSE),
    IP_UNBLOCKED(Severity.INFO, Category.RESPONSE),
    IP_BLOCKED_REQUEST(Severity.MEDIUM, Category.ACCESS),
    ROLE_REVOKED(Severity.HIGH, Category.ACCOUNT),
    API_AUTH_FAILED(Severity.MEDIUM, Category.ACCESS),
    ACCOUNT_LOCKED(Severity.HIGH, Category.ACCOUNT),
    ACCOUNT_UNLOCKED(Severity.INFO, Category.ACCOUNT);

    /** 관리자 화면에서 이벤트를 묶어 보는 분류. */
    public enum Category {
        AUTH("인증·세션"),
        ACCESS("접근 제어"),
        TRANSFER("이체"),
        OPENBANKING("오픈뱅킹"),
        ACCOUNT("계정·권한"),
        RESPONSE("보안 대응");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 이 분류에 속한 이벤트 종류. */
        public java.util.List<SecurityEventType> types() {
            return java.util.Arrays.stream(SecurityEventType.values()).filter(t -> t.category == this).toList();
        }
    }

    private final Severity defaultSeverity;
    private final Category category;

    SecurityEventType(Severity defaultSeverity, Category category) {
        this.defaultSeverity = defaultSeverity;
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public Severity defaultSeverity() {
        return defaultSeverity;
    }
}
