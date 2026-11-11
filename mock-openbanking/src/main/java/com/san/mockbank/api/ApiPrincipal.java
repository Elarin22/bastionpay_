package com.san.mockbank.api;

import com.san.mockbank.client.OauthClient;
import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.oauth.AccessToken;

import java.util.Arrays;

/** Bearer 토큰으로 확인한 호출자. customer 가 null 이면 이용기관 토큰이다. */
public record ApiPrincipal(AccessToken token, OauthClient client, BankCustomer customer) {

    public boolean hasScope(String scope) {
        return Arrays.asList(token.getScope().split(" ")).contains(scope);
    }

    /** 필요한 권한 범위 중 하나라도 있으면 통과한다. */
    public void requireAnyScope(String... scopes) {
        for (String scope : scopes) {
            if (hasScope(scope)) return;
        }
        throw new ApiException(RspCode.INSUFFICIENT_SCOPE, "scope=" + token.getScope());
    }

    /** 사용자 토큰이어야 하는 API 에서 이용기관 토큰으로 호출한 경우를 막는다. */
    public BankCustomer requireCustomer() {
        if (customer == null) throw new ApiException(RspCode.INSUFFICIENT_SCOPE, "이용기관 토큰으로 사용자 API 호출");
        return customer;
    }
}
