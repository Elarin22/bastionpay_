package com.san.bastionpay.openbanking;

import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.san.bastionpay.openbanking.OpenBankingClient.Receiver;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 실제 금융결제원 서버 없이, 요청 형태와 응답 처리만 확인한다(응답 예시는 공개 자료 기준). */
class RestOpenBankingClientTest {

    private static final String BASE = "https://testapi.openbanking.or.kr";
    private final OpenBankingProperties props = new OpenBankingProperties(BASE, "cid", "csecret",
            "https://localhost:8080/openbanking/callback", "M202112345", "secret", Duration.ofMinutes(10),
            Duration.ofSeconds(3), Duration.ofSeconds(10), null);

    private MockRestServiceServer server;
    private RestOpenBankingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestOpenBankingClient(props, builder.build());
    }

    @Test
    void exchangeCodePostsFormAndParsesToken() {
        server.expect(requestTo(BASE + "/oauth/2.0/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                        org.hamcrest.Matchers.containsString("code=abc"),
                        org.hamcrest.Matchers.containsString("client_id=cid"),
                        org.hamcrest.Matchers.containsString("client_secret=csecret"))))
                .andRespond(withSuccess("""
                        {"access_token":"tok-1","token_type":"Bearer","expires_in":7775999,
                         "refresh_token":"r","scope":"login inquiry","user_seq_no":"1100000001","extra":"x"}
                        """, MediaType.APPLICATION_JSON));

        OpenBankingClient.Token token = client.exchangeCode("abc", props.redirectUri());

        assertThat(token.accessToken()).isEqualTo("tok-1");
        assertThat(token.userSeqNo()).isEqualTo("1100000001");
        assertThat(token.expiresInSeconds()).isEqualTo(7775999L);
        assertThat(token.toString()).doesNotContain("tok-1");
        server.verify();
    }

    @Test
    void tokenErrorBecomesAuthFailed() {
        server.expect(requestTo(BASE + "/oauth/2.0/token"))
                .andRespond(withSuccess("{\"error\":\"invalid_grant\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeCode("bad", props.redirectUri()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_AUTH_FAILED));
    }

    @Test
    void accountsSendsBearerAndMapsList() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2.0/account/list")))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(queryParam("user_seq_no", "1100000001"))
                .andRespond(withSuccess("""
                        {"rsp_code":"A0000","rsp_message":"","res_cnt":"1","res_list":[
                          {"fintech_use_num":"199999999999999999999999","account_alias":"월급통장","bank_name":"한국은행",
                           "account_num_masked":"110-***-000000","account_holder_name":"홍*동","inquiry_agree_yn":"Y"}]}
                        """, MediaType.APPLICATION_JSON));

        List<OpenBankingClient.Account> accounts = client.accounts("tok-1", "1100000001");

        assertThat(accounts).singleElement().satisfies(a -> {
            assertThat(a.fintechUseNum()).isEqualTo("199999999999999999999999");
            assertThat(a.bankName()).isEqualTo("한국은행");
            assertThat(a.maskedNumber()).isEqualTo("110-***-000000");
        });
    }

    @Test
    void nonSuccessRspCodeBecomesApiError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2.0/account/list")))
                .andRespond(withSuccess("{\"rsp_code\":\"A0001\",\"rsp_message\":\"fail\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.accounts("t", "u"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_API_ERROR);
                    assertThat(e.getMessage()).contains("A0001");
                });
    }

    @Test
    void balanceBuildsTranIdFromOrgCodeAndParsesAmounts() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2.0/account/balance/fin_num")))
                .andExpect(queryParam("fintech_use_num", "199"))
                .andExpect(queryParam("bank_tran_id", org.hamcrest.Matchers.matchesPattern("M202112345U[A-Z0-9]{9}")))
                .andExpect(queryParam("tran_dtime", org.hamcrest.Matchers.matchesPattern("\\d{14}")))
                .andRespond(withSuccess("""
                        {"rsp_code":"A0000","bank_name":"한국은행","balance_amt":"1234567","available_amt":"1200000"}
                        """, MediaType.APPLICATION_JSON));

        OpenBankingClient.Balance balance = client.balance("tok-1", "199");

        assertThat(balance.balanceAmt()).isEqualTo(1_234_567L);
        assertThat(balance.availableAmt()).isEqualTo(1_200_000L);
    }

    @Test
    void httpErrorBecomesApiErrorWithoutLeakingBodyToUser() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2.0/account/list")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid_token\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.accounts("t", "u"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_API_ERROR);
                    assertThat(e.getErrorCode().message()).doesNotContain("invalid_token");
                    assertThat(e.getMessage()).contains("401");
                });
    }

    @Test
    void transactionsSendsRangeFiltersAndParsesRows() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2.0/account/transaction_list/fin_num")))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(queryParam("fintech_use_num", "199"))
                .andExpect(queryParam("inquiry_type", "O"))
                .andExpect(queryParam("from_date", "20261001"))
                .andExpect(queryParam("to_date", "20261031"))
                .andExpect(queryParam("sort_order", "D"))
                .andExpect(queryParam("page", "2"))
                .andExpect(queryParam("page_size", "20"))
                .andExpect(queryParam("bank_tran_id", org.hamcrest.Matchers.matchesPattern("M202112345U[A-Z0-9]{9}")))
                .andRespond(withSuccess("""
                        {"rsp_code":"A0000","balance_amt":"2350000","total_cnt":"45","page":2,"page_size":20,"has_next":true,
                         "res_list":[
                           {"tran_date":"20261020","tran_time":"093000","inout_type":"출금","tran_type":"이체","print_content":"월세","tran_amt":"700000","after_balance_amt":"2350000"},
                           {"tran_date":"20261019","tran_time":"180500","inout_type":"입금","tran_type":"이체","print_content":"용돈","tran_amt":"50000","after_balance_amt":"3050000"}]}
                        """, MediaType.APPLICATION_JSON));

        OpenBankingClient.TransactionPage page = client.transactions("tok-1", "199", java.time.LocalDate.of(2026, 10, 1),
                java.time.LocalDate.of(2026, 10, 31), "O", 2, 20);

        assertThat(page.totalCount()).isEqualTo(45);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.balanceAmt()).isEqualTo(2_350_000L);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).at()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 20, 9, 30, 0));
        assertThat(page.items().get(0).deposit()).isFalse();
        assertThat(page.items().get(0).amount()).isEqualTo(700_000L);
        assertThat(page.items().get(1).deposit()).isTrue();
        assertThat(page.items().get(1).balanceAfter()).isEqualTo(3_050_000L);
    }

    @Test
    void transactionsErrorCodeBecomesApiError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2.0/account/transaction_list/fin_num")))
                .andRespond(withSuccess("{\"rsp_code\":\"A0005\",\"rsp_message\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.transactions("t", "199", java.time.LocalDate.now(), java.time.LocalDate.now(), "A", 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_API_ERROR);
                    assertThat(e.getMessage()).contains("A0005");
                });
    }

    // ---- 이체 ----

    private RestOpenBankingClient transferClient() {
        OpenBankingProperties p = new OpenBankingProperties(BASE, "cid", "csecret", "https://localhost:8080/openbanking/callback",
                "M202112345", "secret", Duration.ofMinutes(10), Duration.ofSeconds(3), Duration.ofSeconds(10), "dev-pass");
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        return new RestOpenBankingClient(p, builder.build());
    }

    @Test
    void withdrawPostsJsonWithBearerAndParsesBalance() {
        RestOpenBankingClient c = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/withdraw/fin_num"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.bank_tran_id").value("M202112345UAAAAAAAA1"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.fintech_use_num").value("199"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.tran_amt").value("5000"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.print_content").value("BastionPay 이체"))
                .andRespond(withSuccess("{\"rsp_code\":\"A0000\",\"balance_amt\":\"2345000\"}", MediaType.APPLICATION_JSON));

        assertThat(c.withdraw("user-token", "M202112345UAAAAAAAA1", "199", 5_000, "BastionPay 이체").balanceAfter()).isEqualTo(2_345_000L);
        server.verify();
    }

    @Test
    void withdrawMapsBankRejectionCodesToUserFacingErrors() {
        String[][] cases = {{"A0010", "A002"}, {"A0008", "A004"}, {"A0004", "A004"}, {"A0011", "A005"}, {"A0005", "A001"}, {"A0002", "A003"}, {"A0099", "O001"}};
        for (String[] c : cases) {
            RestOpenBankingClient client = transferClient();
            server.expect(requestTo(BASE + "/v2.0/transfer/withdraw/fin_num"))
                    .andRespond(withSuccess("{\"rsp_code\":\"" + c[0] + "\",\"rsp_message\":\"x\"}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.withdraw("t", "id", "199", 1, null)).as(c[0])
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e).isNotInstanceOf(TransferUnknownException.class);
                        assertThat(e.getErrorCode().code()).isEqualTo(c[1]);
                    });
        }
    }

    @Test
    void transferHttpErrorsAreSplitIntoRejectedAndUnknown() {
        // 4xx 는 은행이 거절한 것이 확실하다
        RestOpenBankingClient c1 = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/withdraw/fin_num")).andRespond(withStatus(HttpStatus.FORBIDDEN).body("{}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> c1.withdraw("t", "id", "199", 1, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSFER_NOT_PERMITTED));
        RestOpenBankingClient c2 = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/withdraw/fin_num")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> c2.withdraw("t", "id", "199", 1, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_NOT_LINKED));
        // 5xx 와 연결 오류·시간 초과는 돈이 움직였는지 알 수 없다
        RestOpenBankingClient c3 = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/withdraw/fin_num")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> c3.withdraw("t", "id", "199", 1, null)).isInstanceOf(TransferUnknownException.class);
        RestOpenBankingClient c4 = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/withdraw/fin_num")).andRespond(withException(new java.io.IOException("read timed out")));
        assertThatThrownBy(() -> c4.withdraw("t", "id", "199", 1, null)).isInstanceOf(TransferUnknownException.class);
        RestOpenBankingClient c5 = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/deposit/acnt_num")).andRespond(withException(new java.io.IOException("connection reset")));
        assertThatThrownBy(() -> c5.deposit("t", "id", new Receiver.ByAccount("901", "2003456789012"), 1, null)).isInstanceOf(TransferUnknownException.class);
    }

    @Test
    void depositByAccountSendsPassPhraseAndParsesHolderName() {
        RestOpenBankingClient c = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/deposit/acnt_num"))
                .andExpect(header("Authorization", "Bearer org-token"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.wd_pass_phrase").value("dev-pass"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.req_cnt").value(1))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.req_list[0].tran_no").value(1))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.req_list[0].bank_code_std").value("901"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.req_list[0].account_num").value("2003456789012"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.req_list[0].tran_amt").value("5000"))
                .andRespond(withSuccess("""
                        {"rsp_code":"A0000","res_cnt":"1","res_list":[{"tran_no":1,"rsp_code":"A0000","rsp_message":"","account_holder_name":"브***피"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(c.deposit("org-token", "id", new Receiver.ByAccount("901", "2003456789012"), 5_000, "용돈").holderName()).isEqualTo("브***피");
    }

    @Test
    void depositByFintechUseNumUsesTheOtherEndpoint() {
        RestOpenBankingClient c = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/deposit/fin_num"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.req_list[0].fintech_use_num").value("199"))
                .andRespond(withSuccess("""
                        {"rsp_code":"A0000","res_list":[{"tran_no":1,"rsp_code":"A0000","account_holder_name":"홍*동"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(c.deposit("org-token", "id", new Receiver.ByFintechUseNum("199"), 5_000, null).holderName()).isEqualTo("홍*동");
    }

    @Test
    void depositItemFailureAndWholeRequestRejectionAreRejectedNotUnknown() {
        RestOpenBankingClient c1 = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/deposit/acnt_num")).andRespond(withSuccess("""
                {"rsp_code":"A0012","res_list":[{"tran_no":1,"rsp_code":"A0005","rsp_message":"x"}]}
                """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> c1.deposit("t", "id", new Receiver.ByAccount("901", "2003456789012"), 1, null)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e).isNotInstanceOf(TransferUnknownException.class);
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        });
        // 암호문구 오류처럼 요청 전체가 거절되면 항목 없이 rsp_code 만 온다
        RestOpenBankingClient c2 = transferClient();
        server.expect(requestTo(BASE + "/v2.0/transfer/deposit/acnt_num")).andRespond(withSuccess("{\"rsp_code\":\"A0009\",\"rsp_message\":\"x\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> c2.deposit("t", "id", new Receiver.ByAccount("901", "2003456789012"), 1, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_API_ERROR));
    }

    @Test
    void clientTokenUsesClientCredentialsGrantWithOobScope() {
        RestOpenBankingClient c = transferClient();
        server.expect(requestTo(BASE + "/oauth/2.0/token"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("grant_type=client_credentials"),
                        org.hamcrest.Matchers.containsString("scope=oob"), org.hamcrest.Matchers.containsString("client_id=cid"))))
                .andRespond(withSuccess("{\"access_token\":\"org-1\",\"token_type\":\"Bearer\",\"expires_in\":86400,\"scope\":\"oob\"}", MediaType.APPLICATION_JSON));

        assertThat(c.clientToken()).isEqualTo("org-1");
    }

    @Test
    void newBankTranIdHasOrgCodePrefix() {
        assertThat(client.newBankTranId()).matches("M202112345U[A-Z0-9]{9}");
    }
}
