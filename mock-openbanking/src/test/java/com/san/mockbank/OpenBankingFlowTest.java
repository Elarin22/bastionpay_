package com.san.mockbank;

import com.san.mockbank.account.BankTransaction;
import com.san.mockbank.account.BankTransactionRepository;
import com.san.mockbank.account.InOutType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 화면 → 토큰 → 계좌·잔액 조회까지의 전체 흐름과 보안 규칙을 확인한다.
 * db/seed/mockbank.sql 이 적용된 로컬 mockbank-mysql(3307)이 필요하고, 모든 테스트는 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenBankingFlowTest {

    private static final String CLIENT_ID = "bastionpay-dev-client";
    private static final String SECRET = "bastionpay-dev-secret";
    private static final String REDIRECT = "https://localhost:8080/openbanking/callback";
    private static final String ORG = "M202610001";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BankTransactionRepository transactionRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired com.san.mockbank.account.AccountConsentRepository consentRepository;

    /** 개발 중 직접 연결해 본 동의가 DB 에 남아 있어도 테스트가 흔들리지 않게 한다(이 테스트 트랜잭션 안에서만 지우고 롤백된다). */
    @org.junit.jupiter.api.BeforeEach
    void clearExistingConsents() {
        consentRepository.deleteAllInBatch();
        jdbc.update("delete from sms_verification");   // 번호당 재발송·시간당 횟수 제한이 직접 해 본 기록에 영향받지 않게
    }

    /** 시드의 핀테크이용번호 규칙: 1999 + 계좌 id 를 20자리로 채운 값. */
    private static String fin(int accountId) {
        return "1999" + String.format("%020d", accountId);
    }

    private static String tranId(String suffix) {
        return ORG + "U" + suffix;
    }

    private MvcResult startAuthorize(MockHttpSession session, String scope) throws Exception {
        return mvc.perform(get("/oauth/2.0/authorize").session(session)
                        .param("response_type", "code").param("client_id", CLIENT_ID).param("redirect_uri", REDIRECT)
                        .param("scope", scope).param("state", "state-1234567890").param("auth_type", "0"))
                .andReturn();
    }

    /** 인증 화면을 끝까지 진행해 인증 코드를 얻는다. accountIds 가 비어 있으면 동의 단계의 응답을 그대로 돌려준다. */
    private String authorizeAndGetCode(String loginId, String scope, int... accountIds) throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(startAuthorize(session, scope).getResponse().getStatus()).isEqualTo(200);
        PhoneLogin.login(mvc, jdbc, session, loginId);
        var consent = post("/oauth/2.0/authorize/consent").session(session).with(csrf());
        for (int id : accountIds) consent.param("accountId", String.valueOf(id));
        MvcResult result = mvc.perform(consent).andExpect(status().isFound()).andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith(REDIRECT + "?");
        var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo("state-1234567890");
        return params.getFirst("code");
    }

    private MvcResult token(String code) throws Exception {
        return mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "authorization_code").param("code", code).param("client_id", CLIENT_ID)
                .param("client_secret", SECRET).param("redirect_uri", REDIRECT)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String accessToken(String loginId, String scope, int... accountIds) throws Exception {
        MvcResult r = token(authorizeAndGetCode(loginId, scope, accountIds));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r).get("access_token").asString();
    }

    @Test
    void fullFlowReturnsOnlyConsentedAccountsAndBalance() throws Exception {
        MockHttpSession probe = new MockHttpSession();
        MvcResult start = startAuthorize(probe, "login inquiry");
        assertThat(start.getResponse().getContentAsString()).contains("휴대폰 본인 인증", "BastionPay");
        // 이용기관 주소로 돌려보내는 이동이 막히지 않도록 그 출처만 form-action 에 허용한다
        assertThat(start.getResponse().getHeader("Content-Security-Policy")).contains("form-action 'self' https://localhost:8080");

        MvcResult tokenResult = token(authorizeAndGetCode("test@test.com", "login inquiry", 2));
        JsonNode t = body(tokenResult);
        assertThat(t.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(t.get("user_seq_no").asString()).isEqualTo("1100000002");
        assertThat(t.get("expires_in").asLong()).isGreaterThan(0);
        assertThat(tokenResult.getResponse().getHeader("Cache-Control")).contains("no-store");
        String access = t.get("access_token").asString();

        // 계좌 2(월급 통장)만 동의했으므로 목록에는 그 계좌만 나온다(계좌 3 생활비는 보이지 않는다)
        mvc.perform(get("/v2.0/account/list").header("Authorization", "Bearer " + access)
                        .param("user_seq_no", "1100000002").param("include_cancel_yn", "N").param("sort_order", "D"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsp_code").value("A0000"))
                .andExpect(jsonPath("$.res_cnt").value("1"))
                .andExpect(jsonPath("$.res_list[0].fintech_use_num").value(fin(2)))
                .andExpect(jsonPath("$.res_list[0].account_alias").value("월급 통장"))
                .andExpect(jsonPath("$.res_list[0].account_num_masked").value("100-***-8901"))
                .andExpect(jsonPath("$.res_list[0].transfer_agree_yn").value("N"));

        mvc.perform(get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + access)
                        .param("bank_tran_id", tranId("AAAAAAAA1")).param("fintech_use_num", fin(2)).param("tran_dtime", "20261021101500"))
                .andExpect(jsonPath("$.rsp_code").value("A0000"))
                .andExpect(jsonPath("$.balance_amt").value("102350000"))
                .andExpect(jsonPath("$.bank_tran_id").value(tranId("AAAAAAAA1")));
    }

    @Test
    void balanceRulesAreEnforced() throws Exception {
        String access = accessToken("test@test.com", "login inquiry", 2);

        // 같은 거래고유번호 재사용
        var ok = get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + access)
                .param("bank_tran_id", tranId("BBBBBBBB1")).param("fintech_use_num", fin(2)).param("tran_dtime", "20261021101500");
        mvc.perform(ok).andExpect(jsonPath("$.rsp_code").value("A0000"));
        mvc.perform(ok).andExpect(jsonPath("$.rsp_code").value("A0002"));

        // 이용기관코드가 다르거나 형식이 틀린 거래고유번호
        for (String bad : new String[]{"M202699999UBBBBBBBB2", ORG + "ubbbbbbbb3", ORG + "UBBBB", ""}) {
            mvc.perform(get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + access)
                            .param("bank_tran_id", bad).param("fintech_use_num", fin(2)).param("tran_dtime", "20261021101500"))
                    .andExpect(jsonPath("$.rsp_code").value("A0001"));
        }
        // 잘못된 조회 시각
        mvc.perform(get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + access)
                        .param("bank_tran_id", tranId("BBBBBBBB4")).param("fintech_use_num", fin(2)).param("tran_dtime", "20269999999999"))
                .andExpect(jsonPath("$.rsp_code").value("A0001"));

        // 동의하지 않은 내 계좌(3), 다른 고객의 계좌(4), 없는 계좌는 모두 같은 오류
        int n = 5;
        for (String target : new String[]{fin(3), fin(4), fin(999)}) {
            mvc.perform(get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + access)
                            .param("bank_tran_id", tranId("CCCCCCCC" + n++)).param("fintech_use_num", target).param("tran_dtime", "20261021101500"))
                    .andExpect(jsonPath("$.rsp_code").value("A0005"));
        }
    }

    @Test
    void apiRejectsMissingOrUnknownTokenAndOtherUsersSeqNo() throws Exception {
        mvc.perform(get("/v2.0/account/list").param("user_seq_no", "1100000002"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.rsp_code").value("A0003"));
        mvc.perform(get("/v2.0/account/list").header("Authorization", "Bearer nope").param("user_seq_no", "1100000002"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.rsp_code").value("A0003"));

        String access = accessToken("test@test.com", "login inquiry", 2);
        mvc.perform(get("/v2.0/account/list").header("Authorization", "Bearer " + access).param("user_seq_no", "1100000003"))
                .andExpect(jsonPath("$.rsp_code").value("A0006"));
    }

    @Test
    void scopeIsEnforcedPerApi() throws Exception {
        String loginOnly = accessToken("test@test.com", "login", 2);

        mvc.perform(get("/v2.0/account/list").header("Authorization", "Bearer " + loginOnly).param("user_seq_no", "1100000002"))
                .andExpect(jsonPath("$.rsp_code").value("A0000"));
        mvc.perform(get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + loginOnly)
                        .param("bank_tran_id", tranId("DDDDDDDD1")).param("fintech_use_num", fin(2)).param("tran_dtime", "20261021101500"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.rsp_code").value("A0004"));
    }

    @Test
    void transferConsentIsReportedWhenRequestedAndSelected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(startAuthorize(session, "login inquiry transfer").getResponse().getStatus()).isEqualTo(200);
        PhoneLogin.login(mvc, jdbc, session, "test@test.com");
        assertThat(mvc.perform(get("/oauth/2.0/authorize/consent").session(session)).andReturn().getResponse().getContentAsString())
                .contains("출금이체도 허용");
        MvcResult done = mvc.perform(post("/oauth/2.0/authorize/consent").session(session).with(csrf())
                .param("accountId", "2").param("transferId", "2")).andExpect(status().isFound()).andReturn();
        String code = UriComponentsBuilder.fromUriString(done.getResponse().getHeader("Location")).build().getQueryParams().getFirst("code");
        String access = body(token(code)).get("access_token").asString();

        mvc.perform(get("/v2.0/account/list").header("Authorization", "Bearer " + access).param("user_seq_no", "1100000002"))
                .andExpect(jsonPath("$.res_list[0].transfer_agree_yn").value("Y"));
    }

    @Test
    void reauthorizingWithAnotherSelectionRevokesPreviousConsent() throws Exception {
        accessToken("test@test.com", "login inquiry", 2);
        String second = accessToken("test@test.com", "login inquiry", 3);

        mvc.perform(get("/v2.0/account/list").header("Authorization", "Bearer " + second).param("user_seq_no", "1100000002"))
                .andExpect(jsonPath("$.res_cnt").value("1")).andExpect(jsonPath("$.res_list[0].fintech_use_num").value(fin(3)));
    }

    // ---- 인증 화면 보안 ----

    @Test
    void unregisteredRedirectUriNeverRedirectsAndShowsError() throws Exception {
        MvcResult r = mvc.perform(get("/oauth/2.0/authorize").param("response_type", "code").param("client_id", CLIENT_ID)
                        .param("redirect_uri", "https://evil.example/cb").param("scope", "login").param("state", "s"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(r.getResponse().getHeader("Location")).isNull();
        assertThat(r.getResponse().getContentAsString()).contains("등록되지 않은 돌아갈 주소");
    }

    @Test
    void redirectUriMustMatchExactly() throws Exception {
        for (String uri : new String[]{REDIRECT + "/", REDIRECT + "?x=1", "http://localhost:8080/openbanking/callback", REDIRECT.toUpperCase()}) {
            mvc.perform(get("/oauth/2.0/authorize").param("response_type", "code").param("client_id", CLIENT_ID)
                            .param("redirect_uri", uri).param("scope", "login").param("state", "s"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void invalidAuthorizeRequestsAreRejected() throws Exception {
        String[][] cases = {
                {"code", "unknown-client", REDIRECT, "login", "s"},
                {"token", CLIENT_ID, REDIRECT, "login", "s"},
                {"code", CLIENT_ID, REDIRECT, "login admin", "s"},
                {"code", CLIENT_ID, REDIRECT, "", "s"},
                {"code", CLIENT_ID, REDIRECT, "login", ""},
        };
        for (String[] c : cases) {
            mvc.perform(get("/oauth/2.0/authorize").param("response_type", c[0]).param("client_id", c[1])
                            .param("redirect_uri", c[2]).param("scope", c[3]).param("state", c[4]))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---- 휴대폰 문자 인증 ----

    @Test
    void phoneNumberIsMaskedOnTheCodeScreen() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");
        PhoneLogin.requestCode(mvc, session, "010-0000-0002");

        String page = mvc.perform(get("/oauth/2.0/authorize/code").session(session)).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("010-****-0002", "개발용 인증번호", "<span>3</span>분 안에").doesNotContain("01000000002", "010-0000-0002");
    }

    @Test
    void wrongCodeShowsRemainingAttemptsAndLocksAfterFive() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");
        String real = PhoneLogin.requestCode(mvc, session, "01000000002");
        String wrong = real.equals("000000") ? "111111" : "000000";

        for (int left = 4; left >= 1; left--) {
            String page = mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", wrong))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("인증번호가 맞지 않습니다.", "(" + left + "회 남음)");
        }
        // 다섯 번째 실패: 인증이 끝나고, 맞는 번호를 넣어도 이어갈 수 없다
        mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", wrong)).andExpect(status().isBadRequest());
        mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", real)).andExpect(status().isBadRequest());
    }

    @Test
    void codeMustBeSixDigitsAndMalformedInputCountsAsAttempt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");
        PhoneLogin.requestCode(mvc, session, "01000000002");

        for (String bad : new String[]{"", "12345", "1234567", "abcdef"}) {
            mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", bad)).andExpect(status().isOk());
        }
        // 형식이 틀린 입력도 시도로 센다: 다섯 번째에 인증이 끝난다
        mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", "12 456")).andExpect(status().isBadRequest());
    }

    @Test
    void expiredCodeIsRejectedAndCanBeRequestedAgain() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");
        String code = PhoneLogin.requestCode(mvc, session, "01000000002");
        jdbc.update("update sms_verification set expires_at = ? where phone = ?", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(1)), "01000000002");
        em.clear();   // JDBC 로 바꾼 값을 JPA 가 다시 읽게 한다

        String page = mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", code))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("유효 시간이 지났습니다");
        // 맞는 인증번호여도 만료 뒤에는 쓸 수 없다. 재발송 간격 안에서는 다시 받을 수 없다
        String resent = mvc.perform(post("/oauth/2.0/authorize/resend").session(session).with(csrf())).andReturn().getResponse().getContentAsString();
        assertThat(resent).contains("초 뒤에 다시 요청해 주세요.");
    }

    @Test
    void resendIsLimitedByIntervalAndInvalidatesThePreviousCode() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");
        String first = PhoneLogin.requestCode(mvc, session, "01000000002");
        jdbc.update("update sms_verification set created_at = ? where phone = ?", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(2)), "01000000002");
        em.clear();

        String page = mvc.perform(post("/oauth/2.0/authorize/resend").session(session).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = java.util.regex.Pattern.compile("id=\"dev-code\">(\\d{6})<").matcher(page).results().findFirst().orElseThrow().group(1);

        // 이전 인증번호는 더 이상 쓸 수 없다(새 인증번호와 우연히 같은 경우는 제외)
        if (!first.equals(second)) {
            mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", first)).andExpect(status().isOk());
        }
        mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", second)).andExpect(status().isFound());
    }

    @Test
    void sendsPerHourAreLimitedPerPhoneNumber() throws Exception {
        for (int i = 0; i < 5; i++) {
            jdbc.update("insert into sms_verification (phone, code_salt, code_hash, expires_at, attempts, created_at) values (?, ?, ?, ?, 0, ?)",
                    "01000000003", "a".repeat(32), "b".repeat(64), java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(10)),
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(5 + i)));
        }
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");

        String page = mvc.perform(post("/oauth/2.0/authorize/phone").session(session).with(csrf()).param("phone", "01000000003"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("인증번호 요청이 너무 많습니다.");
    }

    @Test
    void invalidPhoneNumberFormatIsRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");
        for (String bad : new String[]{"", "12345", "02-123-4567", "01012", "010-1234-56789012", "abc"}) {
            String page = mvc.perform(post("/oauth/2.0/authorize/phone").session(session).with(csrf()).param("phone", bad))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).as(bad).contains("휴대폰 번호를 확인해 주세요");
        }
    }

    @Test
    void unregisteredAndLockedNumbersLookTheSameAndNeverGetThroughWithoutTheRealCode() throws Exception {
        // 등록되지 않은 번호: 인증번호를 보냈다고 똑같이 응답하지만(번호 등록 여부를 알려 주지 않는다) 개발용 인증번호도 보여 주지 않는다
        MockHttpSession unknown = new MockHttpSession();
        startAuthorize(unknown, "login inquiry");
        assertThat(PhoneLogin.requestCode(mvc, unknown, "01099998888")).isNull();
        mvc.perform(post("/oauth/2.0/authorize/code").session(unknown).with(csrf()).param("code", "123456")).andExpect(status().isOk());

        // 잠긴 고객(약정계좌 소유자)은 인증번호가 맞아도 통과하지 못한다
        MockHttpSession locked = new MockHttpSession();
        startAuthorize(locked, "login inquiry");
        String code = PhoneLogin.requestCode(mvc, locked, "01000000001");
        assertThat(code).isNull();   // 활성 고객이 아니므로 문자를 보내지 않는다
    }

    @Test
    void codeIsBoundToTheBrowserSession() throws Exception {
        MockHttpSession mine = new MockHttpSession();
        startAuthorize(mine, "login inquiry");
        String code = PhoneLogin.requestCode(mvc, mine, "01000000002");

        // 다른 브라우저(세션)에서는 같은 인증번호를 넣어도 진행할 수 없다
        MockHttpSession other = new MockHttpSession();
        startAuthorize(other, "login inquiry");
        mvc.perform(post("/oauth/2.0/authorize/code").session(other).with(csrf()).param("code", code)).andExpect(status().isBadRequest());
        mvc.perform(get("/oauth/2.0/authorize/consent").session(other)).andExpect(status().isBadRequest());
    }

    @Test
    void postsWithoutCsrfTokenAreRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login");
        mvc.perform(post("/oauth/2.0/authorize/login").session(session)
                .param("loginId", "test@test.com").param("password", "12345678A!")).andExpect(status().isForbidden());
    }

    @Test
    void cancelRedirectsWithAccessDeniedAndNoCode() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startAuthorize(session, "login inquiry");

        MvcResult r = mvc.perform(post("/oauth/2.0/authorize/cancel").session(session).with(csrf())).andExpect(status().isFound()).andReturn();

        String location = r.getResponse().getHeader("Location");
        assertThat(location).startsWith(REDIRECT + "?").contains("error=access_denied", "state=state-1234567890").doesNotContain("code=");
    }

    // ---- 토큰 엔드포인트 보안 ----

    @Test
    void authorizationCodeCanBeUsedOnlyOnce() throws Exception {
        String code = authorizeAndGetCode("test@test.com", "login inquiry", 2);

        assertThat(token(code).getResponse().getStatus()).isEqualTo(200);
        MvcResult again = token(code);
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(again).get("error").asString()).isEqualTo("invalid_grant");
    }

    @Test
    void tokenRequestChecksClientSecretAndRedirectUri() throws Exception {
        String code = authorizeAndGetCode("test@test.com", "login inquiry", 2);

        MvcResult wrongSecret = mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "authorization_code").param("code", code).param("client_id", CLIENT_ID)
                .param("client_secret", "wrong").param("redirect_uri", REDIRECT)).andReturn();
        assertThat(wrongSecret.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrongSecret).get("error").asString()).isEqualTo("invalid_client");

        MvcResult wrongRedirect = mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "authorization_code").param("code", code).param("client_id", CLIENT_ID)
                .param("client_secret", SECRET).param("redirect_uri", "https://evil.example/cb")).andReturn();
        assertThat(wrongRedirect.getResponse().getStatus()).isEqualTo(400);
        // 응답에는 내부 사유가 없다
        assertThat(wrongRedirect.getResponse().getContentAsString()).doesNotContain("redirect").doesNotContain(code);

        // 실패한 시도로 코드가 소진되지는 않는다: 올바른 요청은 여전히 통한다
        assertThat(token(code).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownCodeAndUnsupportedGrantAreRejected() throws Exception {
        assertThat(token("no-such-code").getResponse().getStatus()).isEqualTo(400);
        mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "password").param("client_id", CLIENT_ID).param("client_secret", SECRET))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unsupported_grant_type"));
    }

    @Test
    void clientCredentialsTokenHasNoCustomerAndCannotCallUserApis() throws Exception {
        MvcResult r = mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "client_credentials").param("client_id", CLIENT_ID).param("client_secret", SECRET)
                .param("scope", "oob")).andExpect(status().isOk()).andReturn();
        JsonNode t = body(r);
        assertThat(t.get("scope").asString()).isEqualTo("oob");
        assertThat(t.has("user_seq_no")).isFalse();

        mvc.perform(get("/v2.0/account/list").header("Authorization", "Bearer " + t.get("access_token").asString())
                        .param("user_seq_no", "1100000002"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.rsp_code").value("A0004"));

        mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "client_credentials").param("client_id", CLIENT_ID).param("client_secret", SECRET)
                        .param("scope", "login"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    // ---- 거래내역조회 ----

    /** 시드와 겹치지 않는 먼 과거(2020년 3월)에 거래 4건을 넣는다. 테스트가 끝나면 롤백된다. */
    private void givenMarch2020Transactions() {
        long acct = 2;
        transactionRepository.save(new BankTransaction(acct, LocalDateTime.of(2020, 3, 1, 9, 0, 0), InOutType.IN, "급여", "3월 급여", 3_000_000, 3_000_000, "본점", null, null));
        transactionRepository.save(new BankTransaction(acct, LocalDateTime.of(2020, 3, 10, 12, 30, 0), InOutType.OUT, "카드", "점심", 12_000, 2_988_000, null, null, null));
        transactionRepository.save(new BankTransaction(acct, LocalDateTime.of(2020, 3, 20, 18, 0, 0), InOutType.OUT, "이체", "월세", 700_000, 2_288_000, null, null, null));
        transactionRepository.save(new BankTransaction(acct, LocalDateTime.of(2020, 3, 31, 23, 59, 59), InOutType.IN, "이체", "용돈", 50_000, 2_338_000, null, null, null));
        transactionRepository.flush();
    }

    /** 기본 파라미터에 extra(이름, 값 쌍)를 덮어써서 요청을 만든다. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder history(String access, String suffix, String... extra) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("bank_tran_id", tranId(suffix));
        params.put("fintech_use_num", fin(2));
        params.put("tran_dtime", "20261021101500");
        params.put("inquiry_type", "A");
        params.put("from_date", "20200301");
        params.put("to_date", "20200331");
        params.put("sort_order", "D");
        for (int i = 0; i + 1 < extra.length; i += 2) params.put(extra[i], extra[i + 1]);
        var b = get("/v2.0/account/transaction_list/fin_num").header("Authorization", "Bearer " + access);
        params.forEach(b::param);
        return b;
    }

    @Test
    void transactionListReturnsRangeNewestFirstWithKoreanLabelsAndStringAmounts() throws Exception {
        givenMarch2020Transactions();
        String access = accessToken("test@test.com", "login inquiry", 2);

        mvc.perform(history(access, "HHHHHHHH1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsp_code").value("A0000"))
                .andExpect(jsonPath("$.total_cnt").value("4"))
                .andExpect(jsonPath("$.has_next").value(false))
                .andExpect(jsonPath("$.res_list.length()").value(4))
                .andExpect(jsonPath("$.res_list[0].print_content").value("용돈"))        // 최신순, 3/31 23:59:59 도 포함
                .andExpect(jsonPath("$.res_list[0].tran_date").value("20200331"))
                .andExpect(jsonPath("$.res_list[0].tran_time").value("235959"))
                .andExpect(jsonPath("$.res_list[0].inout_type").value("입금"))
                .andExpect(jsonPath("$.res_list[0].tran_amt").value("50000"))
                .andExpect(jsonPath("$.res_list[0].after_balance_amt").value("2338000"))
                .andExpect(jsonPath("$.res_list[3].print_content").value("3월 급여"))
                .andExpect(jsonPath("$.res_list[2].inout_type").value("출금"));
    }

    @Test
    void transactionListFiltersByTypeSortsAndPages() throws Exception {
        givenMarch2020Transactions();
        String access = accessToken("test@test.com", "login inquiry", 2);

        mvc.perform(history(access, "HHHHHHHH2", "inquiry_type", "I"))
                .andExpect(jsonPath("$.total_cnt").value("2")).andExpect(jsonPath("$.res_list[0].inout_type").value("입금"))
                .andExpect(jsonPath("$.res_list[1].inout_type").value("입금"));
        mvc.perform(history(access, "HHHHHHHH3", "inquiry_type", "O", "sort_order", "A"))
                .andExpect(jsonPath("$.total_cnt").value("2")).andExpect(jsonPath("$.res_list[0].print_content").value("점심"));

        // 2건씩: 1쪽 → has_next, 2쪽 → 마지막
        mvc.perform(history(access, "HHHHHHHH4", "page", "1", "page_size", "2", "sort_order", "A"))
                .andExpect(jsonPath("$.res_list.length()").value(2)).andExpect(jsonPath("$.has_next").value(true))
                .andExpect(jsonPath("$.res_list[0].print_content").value("3월 급여"));
        mvc.perform(history(access, "HHHHHHHH5", "page", "2", "page_size", "2", "sort_order", "A"))
                .andExpect(jsonPath("$.res_list.length()").value(2)).andExpect(jsonPath("$.has_next").value(false))
                .andExpect(jsonPath("$.res_list[1].print_content").value("용돈"));
        // 범위 밖 기간은 빈 목록
        mvc.perform(history(access, "HHHHHHHH6", "from_date", "20200401", "to_date", "20200430"))
                .andExpect(jsonPath("$.rsp_code").value("A0000")).andExpect(jsonPath("$.res_list.length()").value(0));
    }

    @Test
    void transactionListRejectsBadParametersWithoutConsumingTranId() throws Exception {
        String access = accessToken("test@test.com", "login inquiry", 2);
        String[][] bad = {
                {"inquiry_type", "X"}, {"sort_order", "Z"}, {"from_date", "2020-03-01"}, {"to_date", "20200230"},
                {"from_date", "20200401", "to_date", "20200301"},            // 시작이 끝보다 늦음
                {"from_date", "20200101", "to_date", "20201231"},            // 92일 초과
                {"page", "0"}, {"page_size", "0"}, {"page_size", "101"},
        };
        int n = 0;
        for (String[] extra : bad) {
            mvc.perform(history(access, "IIIIIIII" + n++, extra)).andExpect(jsonPath("$.rsp_code").value("A0001"));
        }
        // 형식이 틀린 요청은 기록되지 않으므로 같은 거래고유번호로 올바르게 다시 호출할 수 있다
        mvc.perform(history(access, "IIIIIIII0")).andExpect(jsonPath("$.rsp_code").value("A0000"));
    }

    @Test
    void transactionListEnforcesTranIdConsentAndScope() throws Exception {
        givenMarch2020Transactions();
        String access = accessToken("test@test.com", "login inquiry", 2);

        mvc.perform(history(access, "JJJJJJJJ1")).andExpect(jsonPath("$.rsp_code").value("A0000"));
        mvc.perform(history(access, "JJJJJJJJ1")).andExpect(jsonPath("$.rsp_code").value("A0002"));     // 거래고유번호 재사용
        // 같은 번호는 잔액조회에도 쓸 수 없다(이용기관 전체에서 유일)
        mvc.perform(get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + access)
                        .param("bank_tran_id", tranId("JJJJJJJJ1")).param("fintech_use_num", fin(2)).param("tran_dtime", "20261021101500"))
                .andExpect(jsonPath("$.rsp_code").value("A0002"));

        // 동의하지 않은 내 계좌, 다른 고객 계좌, 없는 계좌는 같은 오류
        int n = 2;
        for (String target : new String[]{fin(3), fin(4), fin(999)}) {
            mvc.perform(history(access, "JJJJJJJJ" + n++, "fintech_use_num", target))
                    .andExpect(jsonPath("$.rsp_code").value("A0005"));
        }
        // 조회 권한(inquiry)이 없는 토큰
        String loginOnly = accessToken("test@test.com", "login", 2);
        mvc.perform(history(loginOnly, "JJJJJJJJ9")).andExpect(status().isForbidden()).andExpect(jsonPath("$.rsp_code").value("A0004"));
        // 필수 파라미터 누락은 A0001, 토큰 없이 호출하면 401
        mvc.perform(get("/v2.0/account/transaction_list/fin_num")).andExpect(jsonPath("$.rsp_code").value("A0001"));
        mvc.perform(get("/v2.0/account/transaction_list/fin_num").param("bank_tran_id", tranId("JJJJJJJJA")).param("fintech_use_num", fin(2))
                        .param("tran_dtime", "20261021101500").param("from_date", "20200301").param("to_date", "20200331"))
                .andExpect(status().isUnauthorized());
    }
}
