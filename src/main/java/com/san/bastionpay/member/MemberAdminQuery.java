package com.san.bastionpay.member;

/** 관리자 화면의 회원 검색 조건 이름 모음(쿼리 문자열의 키). */
public final class MemberAdminQuery {

    private MemberAdminQuery() {
    }

    /** %, _, ! 를 글자 그대로 찾도록 이스케이프한다(LIKE 와일드카드로 쓰이지 않게). 쿼리의 escape 문자는 '!' 이다. */
    public static String escapeLike(String q) {
        return q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
