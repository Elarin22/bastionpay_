package com.san.bastionpay.admin;

import com.san.bastionpay.audit.Actor;
import com.san.bastionpay.auth.AuthMember;
import org.slf4j.MDC;
import org.springframework.web.util.UriComponentsBuilder;

/** 관리자 화면 컨트롤러들이 같이 쓰는 도구. */
final class AdminActions {

    private AdminActions() {
    }

    static Actor actor(AuthMember admin) {
        return Actor.admin(admin.id(), admin.nickname());
    }

    /** 지금 요청한 사람의 IP(MdcLoggingFilter 가 정한 값). 자기 IP 를 차단하는 실수를 막는 데 쓴다. */
    static String requesterIp() {
        return MDC.get("clientIp");
    }

    /** 처리 뒤 목록 화면으로 되돌아갈 주소. 결과 안내는 notice 코드로 전한다(PRG). */
    static String redirect(String path, String notice, String... queryPairs) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(path);
        for (int i = 0; i + 1 < queryPairs.length; i += 2) if (queryPairs[i + 1] != null && !queryPairs[i + 1].isBlank()) b.queryParam(queryPairs[i], queryPairs[i + 1]);
        if (notice != null) b.queryParam("notice", notice);
        return "redirect:" + b.build().encode().toUriString();
    }
}
