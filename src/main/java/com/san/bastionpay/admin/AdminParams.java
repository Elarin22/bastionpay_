package com.san.bastionpay.admin;

import com.san.bastionpay.audit.IpAddresses;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

/** 관리자 화면의 조회 조건(쿼리 문자열) 해석 도구. 잘못된 값은 "전체"로 취급하고 오류를 내지 않는다. */
final class AdminParams {

    private AdminParams() {
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null || value.isBlank() || "all".equals(value)) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 양의 정수 회원 번호. 비었거나 숫자가 아니면 비어 있다. */
    static Optional<Long> memberId(String value) {
        if (value == null) return Optional.empty();
        String v = value.strip().replaceFirst("^#", "");
        return v.matches("\\d{1,18}") ? Optional.of(Long.parseLong(v)) : Optional.empty();
    }

    static Optional<String> ip(String value) {
        return IpAddresses.normalize(value);
    }

    /** 조건을 유지한 채 쪽만 바꾸는 주소. 값이 비었거나 기본값("all")인 조건은 싣지 않는다. */
    static String href(String path, Map<String, String> params, int page) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(path);
        params.forEach((k, v) -> {
            if (v != null && !v.isBlank() && !"all".equals(v)) b.queryParam(k, v);
        });
        return b.queryParam("page", page).build().encode().toUriString();
    }
}
