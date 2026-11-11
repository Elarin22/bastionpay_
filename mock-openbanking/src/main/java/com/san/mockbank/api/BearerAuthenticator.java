package com.san.mockbank.api;

import com.san.mockbank.client.OauthClient;
import com.san.mockbank.client.OauthClientRepository;
import com.san.mockbank.core.Tokens;
import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.customer.BankCustomerRepository;
import com.san.mockbank.oauth.AccessToken;
import com.san.mockbank.oauth.AccessTokenRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class BearerAuthenticator {

    private static final String PREFIX = "Bearer ";

    private final AccessTokenRepository tokens;
    private final OauthClientRepository clients;
    private final BankCustomerRepository customers;

    public BearerAuthenticator(AccessTokenRepository tokens, OauthClientRepository clients, BankCustomerRepository customers) {
        this.tokens = tokens;
        this.clients = clients;
        this.customers = customers;
    }

    /** Authorization: Bearer 헤더를 확인한다. 없거나 만료·철회됐거나 이용기관·고객이 비활성이면 401. */
    public ApiPrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(PREFIX) || authorization.length() == PREFIX.length()) {
            throw new ApiException(RspCode.INVALID_TOKEN, "Bearer 헤더 없음");
        }
        AccessToken token = tokens.findByTokenHash(Tokens.sha256(authorization.substring(PREFIX.length()).trim()))
                .orElseThrow(() -> new ApiException(RspCode.INVALID_TOKEN, "알 수 없는 토큰"));
        if (token.isRevoked() || token.isExpired(LocalDateTime.now())) {
            throw new ApiException(RspCode.INVALID_TOKEN, "만료되었거나 철회된 토큰");
        }
        OauthClient client = clients.findById(token.getClientPk()).filter(OauthClient::isEnabled)
                .orElseThrow(() -> new ApiException(RspCode.INVALID_TOKEN, "비활성 이용기관"));
        BankCustomer customer = null;
        if (token.getCustomerId() != null) {
            customer = customers.findById(token.getCustomerId()).filter(BankCustomer::isActive)
                    .orElseThrow(() -> new ApiException(RspCode.INVALID_TOKEN, "비활성 고객"));
        }
        return new ApiPrincipal(token, client, customer);
    }
}
