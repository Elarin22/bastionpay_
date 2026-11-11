package com.san.mockbank.oauth;

import java.io.Serializable;
import java.net.URI;
import java.util.List;

/** 검증을 마친 인증 요청. 로그인·동의 단계 동안 세션에 보관한다. */
public record AuthorizeRequest(String clientId, String clientName, String redirectUri, List<String> scopes, String state) implements Serializable {

    public String scope() {
        return String.join(" ", scopes);
    }

    /** 화면에 보여 줄 권한 설명(요청한 순서대로). */
    public List<String> scopeLabels() {
        return scopes.stream().map(AuthorizeRequest::label).toList();
    }

    private static String label(String scope) {
        return switch (scope) {
            case "login" -> "본인 확인";
            case "inquiry" -> "계좌·잔액·거래내역 조회";
            case "transfer" -> "출금이체";
            default -> scope;
        };
    }

    public boolean wantsTransfer() {
        return scopes.contains("transfer");
    }

    /** 화면의 CSP form-action 에 허용할 주소(redirect URI 의 출처). */
    public String redirectOrigin() {
        URI uri = URI.create(redirectUri);
        return uri.getScheme() + "://" + uri.getAuthority();
    }
}
