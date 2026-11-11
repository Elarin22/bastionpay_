package com.san.mockbank.oauth;

import com.san.mockbank.account.AccountConsent;
import com.san.mockbank.account.AccountStatus;
import com.san.mockbank.account.AccountConsentRepository;
import com.san.mockbank.account.BankAccount;
import com.san.mockbank.account.BankAccountRepository;
import com.san.mockbank.client.OauthClient;
import com.san.mockbank.client.OauthClientRepository;
import com.san.mockbank.core.LogSanitizer;
import com.san.mockbank.core.MockbankProperties;
import com.san.mockbank.core.Tokens;
import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.customer.BankCustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사용자 인증 요청 검증, 계좌 동의와 인증 코드 발급, 토큰 발급. 사용자 본인 확인(휴대폰 문자 인증)은 phone 패키지가 맡는다.
 * 인증 화면은 client_id 와 redirect URI 를 먼저 검증한다. 등록된 주소와 글자 하나까지 같지 않으면
 * 인증 코드를 어디로도 보내지 않고 오류 화면만 보여 준다.
 */
@Slf4j
@Service
public class OauthService {

    static final Set<String> SCOPES = Set.of("login", "inquiry", "transfer");
    /** 이용기관이 없을 때도 secret 비교 시간을 비슷하게 맞추기 위한 더미 해시. */
    private static final String DUMMY_HASH = "$2a$12$C6UzMDM.H6dfI/f/IKcEeO5eV1bq7c1Y0l7e1R4s3d1o2z9Qy8w6u";

    private final OauthClientRepository clients;
    private final BankCustomerRepository customers;
    private final BankAccountRepository accounts;
    private final AccountConsentRepository consents;
    private final AuthorizationCodeRepository codes;
    private final AccessTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final MockbankProperties props;

    public OauthService(OauthClientRepository clients, BankCustomerRepository customers, BankAccountRepository accounts,
                        AccountConsentRepository consents, AuthorizationCodeRepository codes, AccessTokenRepository tokens,
                        PasswordEncoder encoder, MockbankProperties props) {
        this.clients = clients;
        this.customers = customers;
        this.accounts = accounts;
        this.consents = consents;
        this.codes = codes;
        this.tokens = tokens;
        this.encoder = encoder;
        this.props = props;
    }

    // ---- 인증 화면 ----

    public AuthorizeRequest validateAuthorizeRequest(String responseType, String clientId, String redirectUri,
                                                     String scope, String state) {
        OauthClient client = clients.findByClientId(clientId == null ? "" : clientId)
                .filter(OauthClient::isEnabled)
                .orElseThrow(() -> new AuthorizeException("등록되지 않은 이용기관입니다."));
        if (!client.allowsRedirectUri(redirectUri)) {
            log.warn("authorize: 등록되지 않은 redirect_uri clientId={}", LogSanitizer.clean(clientId));
            throw new AuthorizeException("등록되지 않은 돌아갈 주소(redirect_uri)입니다.");
        }
        if (!"code".equals(responseType)) throw new AuthorizeException("지원하지 않는 응답 방식입니다.");
        if (state == null || state.isBlank()) throw new AuthorizeException("state 값이 필요합니다.");
        List<String> scopes = scope == null ? List.of() : Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isEmpty()).distinct().toList();
        if (scopes.isEmpty() || !SCOPES.containsAll(scopes)) throw new AuthorizeException("요청한 권한 범위(scope)가 올바르지 않습니다.");
        return new AuthorizeRequest(client.getClientId(), client.getName(), redirectUri, scopes, state);
    }

    public List<BankAccount> selectableAccounts(Long customerId) {
        return accounts.findByCustomerIdOrderByIdAsc(customerId).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE).toList();
    }

    /**
     * 동의한 계좌를 저장하고 인증 코드를 발급한다. 이번에 선택하지 않은 계좌의 이전 동의는 철회한다.
     * 선택한 계좌가 로그인한 고객의 계좌가 아니면 거절한다.
     */
    @Transactional
    public String issueCode(AuthorizeRequest request, Long customerId, Set<Long> accountIds, Set<Long> transferIds) {
        OauthClient client = clients.findByClientId(request.clientId()).filter(OauthClient::isEnabled)
                .orElseThrow(() -> new AuthorizeException("이용기관을 확인할 수 없습니다."));
        List<BankAccount> mine = selectableAccounts(customerId);
        Set<Long> mineIds = mine.stream().map(BankAccount::getId).collect(Collectors.toSet());
        if (accountIds.isEmpty() || !mineIds.containsAll(accountIds)) {
            throw new AuthorizeException("연결할 계좌를 한 개 이상 선택해 주세요.");
        }
        for (BankAccount account : mine) {
            boolean selected = accountIds.contains(account.getId());
            boolean transfer = selected && request.wantsTransfer() && transferIds.contains(account.getId());
            AccountConsent consent = consents.findByClientPkAndAccountId(client.getId(), account.getId()).orElse(null);
            if (selected) {
                if (consent == null) consents.save(new AccountConsent(client.getId(), account.getId(), true, transfer));
                else consent.renew(true, transfer);
            } else if (consent != null) {
                consent.revoke();
            }
        }
        String code = Tokens.random(24);
        codes.save(new AuthorizationCode(Tokens.sha256(code), client.getId(), customerId, request.redirectUri(),
                request.scope(), LocalDateTime.now().plus(props.authCodeTtl())));
        return code;
    }

    /** 인증 결과를 이용기관의 redirect URI 로 돌려보낼 주소. */
    public static String successRedirect(AuthorizeRequest request, String code) {
        return UriComponentsBuilder.fromUriString(request.redirectUri())
                .queryParam("code", code).queryParam("scope", request.scope()).queryParam("state", request.state())
                .build().encode().toUriString();
    }

    public static String deniedRedirect(AuthorizeRequest request) {
        return UriComponentsBuilder.fromUriString(request.redirectUri())
                .queryParam("error", "access_denied").queryParam("state", request.state())
                .build().encode().toUriString();
    }

    // ---- 토큰 엔드포인트 ----

    /** 인증 코드를 사용자 토큰으로 교환한다. */
    @Transactional
    public IssuedToken exchangeCode(String clientId, String clientSecret, String code, String redirectUri) {
        OauthClient client = authenticateClient(clientId, clientSecret);
        if (code == null || code.isBlank() || redirectUri == null) {
            throw new OauthException("invalid_request", HttpStatus.BAD_REQUEST, "code/redirect_uri 누락");
        }
        AuthorizationCode saved = codes.findByCodeHash(Tokens.sha256(code))
                .orElseThrow(() -> new OauthException("invalid_grant", HttpStatus.BAD_REQUEST, "알 수 없는 코드"));
        LocalDateTime now = LocalDateTime.now();
        if (!saved.getClientPk().equals(client.getId())) {
            // 다른 이용기관의 코드를 가져다 쓰는 시도
            log.warn("token: 다른 이용기관의 코드 사용 시도 clientId={}", LogSanitizer.clean(clientId));
            throw new OauthException("invalid_grant", HttpStatus.BAD_REQUEST, "코드의 발급 대상이 다름");
        }
        if (saved.isExpired(now) || !saved.getRedirectUri().equals(redirectUri)) {
            throw new OauthException("invalid_grant", HttpStatus.BAD_REQUEST, "만료됐거나 redirect_uri 가 다름");
        }
        // 동시에 같은 코드가 들어와도 한 요청만 통과한다
        if (saved.isUsed() || codes.markUsed(saved.getId(), now) != 1) {
            log.warn("token: 이미 사용한 인증 코드 재사용 시도 clientId={}", LogSanitizer.clean(clientId));
            throw new OauthException("invalid_grant", HttpStatus.BAD_REQUEST, "이미 사용한 코드");
        }
        BankCustomer customer = customers.findById(saved.getCustomerId())
                .filter(BankCustomer::isActive)
                .orElseThrow(() -> new OauthException("invalid_grant", HttpStatus.BAD_REQUEST, "사용할 수 없는 고객"));
        return issue(client, customer, saved.getScope());
    }

    /** 이용기관 토큰(2-legged). 이용기관 인증만으로 발급하며 고객이 없다. */
    @Transactional
    public IssuedToken clientCredentials(String clientId, String clientSecret, String scope) {
        OauthClient client = authenticateClient(clientId, clientSecret);
        if (!"oob".equals(scope == null ? "" : scope.trim())) {
            throw new OauthException("invalid_scope", HttpStatus.BAD_REQUEST, "이용기관 토큰은 oob 만 요청할 수 있음");
        }
        return issue(client, null, "oob");
    }

    private OauthClient authenticateClient(String clientId, String clientSecret) {
        OauthClient client = clientId == null ? null : clients.findByClientId(clientId).orElse(null);
        boolean matches = encoder.matches(clientSecret == null ? "" : clientSecret, client == null ? DUMMY_HASH : client.getClientSecretHash());
        if (client == null || !matches || !client.isEnabled()) {
            log.warn("token: 이용기관 인증 실패 clientId={}", LogSanitizer.clean(clientId));
            throw new OauthException("invalid_client", HttpStatus.UNAUTHORIZED, "client 인증 실패");
        }
        return client;
    }

    private IssuedToken issue(OauthClient client, BankCustomer customer, String scope) {
        String token = Tokens.random(32);
        long ttlSeconds = "oob".equals(scope) ? Math.min(props.accessTokenTtl().toSeconds(), 24 * 3600L) : props.accessTokenTtl().toSeconds();
        tokens.save(new AccessToken(Tokens.sha256(token), client.getId(), customer == null ? null : customer.getId(), scope,
                LocalDateTime.now().plusSeconds(ttlSeconds)));
        return new IssuedToken(token, ttlSeconds, scope, customer == null ? null : customer.getUserSeqNo());
    }

    /** accessToken 원문은 이 응답에서만 볼 수 있다. */
    public record IssuedToken(String accessToken, long expiresIn, String scope, String userSeqNo) {
        @Override
        public String toString() {
            return "IssuedToken[scope=" + scope + "]";
        }
    }
}
