package com.san.mockbank;

import com.san.mockbank.account.BankAccountRepository;
import com.san.mockbank.account.BankTransactionRepository;
import com.san.mockbank.transfer.BankTransfer;
import com.san.mockbank.transfer.BankTransferItemRepository;
import com.san.mockbank.transfer.BankTransferRepository;
import com.san.mockbank.transfer.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 출금이체·입금이체 API. db/seed/mockbank.sql 이 적용된 로컬 mockbank-mysql(3307)이 필요하고, 모든 테스트는 롤백된다.
 * 시드: 약정계좌 1(5천만 원), 테스터 계좌 2(2,350,000원)·3, vip 계좌 4, vvip 계좌 5.
 */
@SpringBootTest(properties = "mockbank.transfer-max-amount=10000000")   // 은행의 건당 한도 검증을 시험하려고 기본값(사실상 제한 없음)을 낮춘다
@AutoConfigureMockMvc
@Transactional
class TransferApiTest {

    private static final String CLIENT_ID = "bastionpay-dev-client";
    private static final String SECRET = "bastionpay-dev-secret";
    private static final String PASS_PHRASE = "bastionpay-dev-pass";
    private static final String REDIRECT = "https://localhost:8080/openbanking/callback";
    private static final String ORG = "M202610001";
    private static final String WITHDRAW = "/v2.0/transfer/withdraw/fin_num";
    private static final String DEPOSIT = "/v2.0/transfer/deposit/fin_num";
    private static final String DEPOSIT_ACNT = "/v2.0/transfer/deposit/acnt_num";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BankAccountRepository accounts;
    @Autowired BankTransactionRepository transactions;
    @Autowired BankTransferRepository transfers;
    @Autowired BankTransferItemRepository transferItems;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.san.mockbank.account.AccountConsentRepository consentRepository;

    /** 개발 중 직접 연결해 본 동의가 DB 에 남아 있어도 테스트가 흔들리지 않게 한다(이 테스트 트랜잭션 안에서만 지우고 롤백된다). */
    @org.junit.jupiter.api.BeforeEach
    void clearExistingConsents() {
        consentRepository.deleteAllInBatch();
        jdbc.update("delete from sms_verification");
    }

    private static String fin(int accountId) {
        return "1999" + String.format("%020d", accountId);
    }

    private static String tranId(String suffix) {
        return ORG + "U" + suffix;
    }

    private long balance(int accountId) {
        return accounts.findById((long) accountId).orElseThrow().getBalanceAmt();
    }

    // ---- 토큰 얻기 ----

    /** 은행 로그인·동의 화면을 거쳐 사용자 토큰을 얻는다. transferIds 에 든 계좌는 이체도 허용한다. */
    private String userToken(String scope, int[] accountIds, int... transferIds) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/oauth/2.0/authorize").session(session).param("response_type", "code").param("client_id", CLIENT_ID)
                .param("redirect_uri", REDIRECT).param("scope", scope).param("state", "state-1234567890")).andExpect(status().isOk());
        PhoneLogin.login(mvc, jdbc, session, "test@test.com");
        var consent = post("/oauth/2.0/authorize/consent").session(session).with(csrf());
        for (int id : accountIds) consent.param("accountId", String.valueOf(id));
        for (int id : transferIds) consent.param("transferId", String.valueOf(id));
        String location = mvc.perform(consent).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        MvcResult r = mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "authorization_code").param("code", code).param("client_id", CLIENT_ID)
                .param("client_secret", SECRET).param("redirect_uri", REDIRECT)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("access_token").asString();
    }

    private String transferToken() throws Exception {
        return userToken("login inquiry transfer", new int[]{2}, 2);
    }

    private String orgToken() throws Exception {
        MvcResult r = mvc.perform(post("/oauth/2.0/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "client_credentials").param("client_id", CLIENT_ID).param("client_secret", SECRET)
                .param("scope", "oob")).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("access_token").asString();
    }

    // ---- 요청 만들기 ----

    private ResultActions withdraw(String token, String tranId, String finNum, String amount, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bank_tran_id", tranId);
        body.put("tran_dtime", "20261021101500");
        body.put("fintech_use_num", finNum);
        body.put("tran_amt", amount);
        if (content != null) body.put("print_content", content);
        var req = post(WITHDRAW).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    /** items: {핀테크번호, 금액} 쌍. tran_no 는 1부터 차례로 붙인다. */
    private Map<String, Object> depositBody(String tranId, String passPhrase, String[]... items) {
        List<Map<String, Object>> list = new ArrayList<>();
        int no = 1;
        for (String[] item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tran_no", no++);
            m.put("fintech_use_num", item[0]);
            m.put("print_content", "정산금");
            m.put("tran_amt", item[1]);
            list.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bank_tran_id", tranId);
        body.put("tran_dtime", "20261021101500");
        body.put("wd_pass_phrase", passPhrase);
        body.put("wd_print_content", "BastionPay 정산");
        body.put("req_cnt", list.size());
        body.put("req_list", list);
        return body;
    }

    private ResultActions deposit(String token, Map<String, Object> body) throws Exception {
        var req = post(DEPOSIT).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    // ---- 출금이체 ----

    @Test
    void withdrawMovesMoneyToEscrowAndRecordsBothSides() throws Exception {
        String token = transferToken();
        long userBefore = balance(2), escrowBefore = balance(1);
        long transactionsBefore = transactions.count();

        withdraw(token, tranId("WWWWWWWW1"), fin(2), "100000", "테스트 출금")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsp_code").value("A0000"))
                .andExpect(jsonPath("$.tran_amt").value("100000"))
                .andExpect(jsonPath("$.balance_amt").value(String.valueOf(userBefore - 100_000)));

        assertThat(balance(2)).isEqualTo(userBefore - 100_000);
        assertThat(balance(1)).isEqualTo(escrowBefore + 100_000);
        assertThat(transactions.count()).isEqualTo(transactionsBefore + 2);          // 출금 한 건 + 약정계좌 입금 한 건
        BankTransfer transfer = transfers.findAll().stream().filter(t -> t.getBankTranId().equals(tranId("WWWWWWWW1"))).findFirst().orElseThrow();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transferItems.findByTransferIdOrderByTranNo(transfer.getId())).singleElement()
                .satisfies(i -> assertThat(i.getTranAmt()).isEqualTo(100_000));

        // 거래내역에는 이 출금이 보인다
        mvc.perform(get("/v2.0/account/balance/fin_num").header("Authorization", "Bearer " + userToken("login inquiry", new int[]{2}))
                        .param("bank_tran_id", tranId("WWWWWWWW2")).param("fintech_use_num", fin(2)).param("tran_dtime", "20261021101500"))
                .andExpect(jsonPath("$.balance_amt").value(String.valueOf(userBefore - 100_000)));
    }

    @Test
    void withdrawRejectsInsufficientBalanceAndLimitWithoutChangingAnything() throws Exception {
        String token = transferToken();
        // 시드 잔액은 한도(1천만 원)보다 커서, 잔액 부족을 시험하려면 잔액을 한도 아래로 낮춘다(테스트 트랜잭션 안이라 롤백된다)
        accounts.findById(2L).orElseThrow().withdraw(balance(2) - 5_000_000L);
        accounts.flush();
        long userBefore = balance(2), escrowBefore = balance(1);
        long transactionsBefore = transactions.count();

        withdraw(token, tranId("WWWWWWWW3"), fin(2), String.valueOf(userBefore + 1), null).andExpect(jsonPath("$.rsp_code").value("A0010"));
        withdraw(token, tranId("WWWWWWWW4"), fin(2), "10000001", null).andExpect(jsonPath("$.rsp_code").value("A0011"));      // 한도 초과

        assertThat(balance(2)).isEqualTo(userBefore);
        assertThat(balance(1)).isEqualTo(escrowBefore);
        assertThat(transactions.count()).isEqualTo(transactionsBefore);
        // 실패한 요청의 결과 코드가 이체 요청 기록에 남는다
        assertThat(transfers.findAll().stream().filter(t -> t.getBankTranId().equals(tranId("WWWWWWWW3"))).findFirst().orElseThrow().getStatus())
                .isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void withdrawIsExactlyOncePerTranId() throws Exception {
        String token = transferToken();
        long userBefore = balance(2);

        withdraw(token, tranId("WWWWWWWW5"), fin(2), "50000", null).andExpect(jsonPath("$.rsp_code").value("A0000"));
        withdraw(token, tranId("WWWWWWWW5"), fin(2), "50000", null).andExpect(jsonPath("$.rsp_code").value("A0002"));

        assertThat(balance(2)).isEqualTo(userBefore - 50_000);
    }

    @Test
    void withdrawRejectsBadAmountsAndFormatsWithoutConsumingTranId() throws Exception {
        String token = transferToken();
        long userBefore = balance(2);
        String[] badAmounts = {"0", "-1000", "1.5", "01000", "abc", "", "1000000000000000", " 100"};
        int n = 0;
        for (String amount : badAmounts) {
            withdraw(token, tranId("BADAMT00" + n++), fin(2), amount, null).andExpect(jsonPath("$.rsp_code").value("A0001"));
        }
        withdraw(token, "M202699999UWWWWWWWW6", fin(2), "1000", null).andExpect(jsonPath("$.rsp_code").value("A0001")); // 다른 이용기관코드
        withdraw(token, tranId("WWWWWWWW7"), "123", "1000", null).andExpect(jsonPath("$.rsp_code").value("A0001"));       // 핀테크번호 형식
        withdraw(token, tranId("WWWWWWWW8"), fin(2), "1000", "가".repeat(21)).andExpect(jsonPath("$.rsp_code").value("A0001"));   // 적요 20자 초과
        assertThat(balance(2)).isEqualTo(userBefore);

        // 형식 오류는 거래고유번호를 소모하지 않는다: 같은 번호로 올바르게 다시 보낼 수 있다
        withdraw(token, tranId("BADAMT000"), fin(2), "1000", null).andExpect(jsonPath("$.rsp_code").value("A0000"));
    }

    @Test
    void withdrawRequiresTransferScopeConsentAndOwnership() throws Exception {
        String transferOnlyFor2 = transferToken();
        long userBefore = balance(2);

        // 내가 이체를 동의하지 않은 계좌(3), 다른 고객 계좌(4), 약정계좌(1), 없는 계좌는 모두 같은 오류
        int n = 0;
        for (int target : new int[]{3, 4, 1, 999}) {
            withdraw(transferOnlyFor2, tranId("OWNER000" + n++), fin(target), "1000", null).andExpect(jsonPath("$.rsp_code").value("A0005"));
        }
        // 조회만 동의하고 이체는 동의하지 않은 계좌
        String inquiryOnly = userToken("login inquiry transfer", new int[]{2});
        withdraw(inquiryOnly, tranId("NOTRANS01"), fin(2), "1000", null).andExpect(jsonPath("$.rsp_code").value("A0008"));
        // transfer 권한(scope)이 없는 토큰
        String noTransferScope = userToken("login inquiry", new int[]{2});
        withdraw(noTransferScope, tranId("NOSCOPE01"), fin(2), "1000", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.rsp_code").value("A0004"));
        // 이용기관 토큰으로는 사용자 계좌에서 출금할 수 없다
        withdraw(orgToken(), tranId("ORGTOKEN1"), fin(2), "1000", null).andExpect(status().isForbidden());
        withdraw(null, tranId("NOTOKEN01"), fin(2), "1000", null).andExpect(status().isUnauthorized());
        assertThat(balance(2)).isEqualTo(userBefore);
    }

    // ---- 입금이체 ----

    @Test
    void depositSupportsPartialSuccessAndMasksReceiverNames() throws Exception {
        String token = orgToken();
        long escrowBefore = balance(1), vipBefore = balance(4), vvipBefore = balance(5);

        deposit(token, depositBody(tranId("DDDDDDDD1"), PASS_PHRASE,
                new String[]{fin(4), "1000000"}, new String[]{fin(999), "5000"}, new String[]{fin(5), "2000000"}, new String[]{fin(1), "100"}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsp_code").value("A0007"))
                .andExpect(jsonPath("$.res_cnt").value("4"))
                .andExpect(jsonPath("$.res_list[0].rsp_code").value("A0000"))
                .andExpect(jsonPath("$.res_list[0].account_holder_name").value("브***피"))
                .andExpect(jsonPath("$.res_list[1].rsp_code").value("A0005"))               // 없는 계좌
                .andExpect(jsonPath("$.res_list[1].account_holder_name").doesNotExist())
                .andExpect(jsonPath("$.res_list[2].rsp_code").value("A0000"))
                .andExpect(jsonPath("$.res_list[3].rsp_code").value("A0005"));              // 약정계좌로 보내기

        assertThat(balance(4)).isEqualTo(vipBefore + 1_000_000);
        assertThat(balance(5)).isEqualTo(vvipBefore + 2_000_000);
        assertThat(balance(1)).isEqualTo(escrowBefore - 3_000_000);                         // 성공한 두 건만 빠진다
        BankTransfer transfer = transfers.findAll().stream().filter(t -> t.getBankTranId().equals(tranId("DDDDDDDD1"))).findFirst().orElseThrow();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PARTIAL);
        assertThat(transferItems.findByTransferIdOrderByTranNo(transfer.getId())).extracting(i -> i.getRspCode())
                .containsExactly("A0000", "A0005", "A0000", "A0005");
    }

    @Test
    void depositFailsItemsThatExceedEscrowBalanceOrLimit() throws Exception {
        String token = orgToken();
        long escrowBefore = balance(1);
        // 천만 원씩 6건: 약정계좌 5천만 원으로는 5건까지만 가능하다
        List<String[]> items = new ArrayList<>();
        for (int i = 0; i < 6; i++) items.add(new String[]{fin(4), "10000000"});

        deposit(token, depositBody(tranId("DDDDDDDD2"), PASS_PHRASE, items.toArray(new String[0][])))
                .andExpect(jsonPath("$.rsp_code").value("A0007"))
                .andExpect(jsonPath("$.res_list[4].rsp_code").value("A0000"))
                .andExpect(jsonPath("$.res_list[5].rsp_code").value("A0010"));
        assertThat(balance(1)).isEqualTo(escrowBefore - 50_000_000L);

        // 한도 초과(10,000,001원)는 그 항목만 실패한다. 전부 실패하면 A0012
        deposit(token, depositBody(tranId("DDDDDDDD3"), PASS_PHRASE, new String[]{fin(4), "10000001"}))
                .andExpect(jsonPath("$.rsp_code").value("A0012")).andExpect(jsonPath("$.res_list[0].rsp_code").value("A0011"));
    }

    @Test
    void depositRequiresPassPhraseOrgTokenAndValidRequest() throws Exception {
        String token = orgToken();
        long vipBefore = balance(4);

        deposit(token, depositBody(tranId("DDDDDDDD4"), "wrong-phrase", new String[]{fin(4), "1000"})).andExpect(jsonPath("$.rsp_code").value("A0009"));
        deposit(token, depositBody(tranId("DDDDDDDD4"), null, new String[]{fin(4), "1000"})).andExpect(jsonPath("$.rsp_code").value("A0009"));
        // 사용자 토큰·토큰 없음
        deposit(transferToken(), depositBody(tranId("DDDDDDDD5"), PASS_PHRASE, new String[]{fin(4), "1000"})).andExpect(status().isForbidden());
        deposit(null, depositBody(tranId("DDDDDDDD5"), PASS_PHRASE, new String[]{fin(4), "1000"})).andExpect(status().isUnauthorized());

        // 요청 형식 오류: 건수 불일치, tran_no 순서 오류, 빈 목록, 101건, 금액 형식
        Map<String, Object> mismatch = depositBody(tranId("DDDDDDDD6"), PASS_PHRASE, new String[]{fin(4), "1000"});
        mismatch.put("req_cnt", 2);
        deposit(token, mismatch).andExpect(jsonPath("$.rsp_code").value("A0001"));
        Map<String, Object> badNo = depositBody(tranId("DDDDDDDD7"), PASS_PHRASE, new String[]{fin(4), "1000"}, new String[]{fin(5), "1000"});
        ((List<Map<String, Object>>) badNo.get("req_list")).get(1).put("tran_no", 5);
        deposit(token, badNo).andExpect(jsonPath("$.rsp_code").value("A0001"));
        deposit(token, depositBody(tranId("DDDDDDDD8"), PASS_PHRASE)).andExpect(jsonPath("$.rsp_code").value("A0001"));
        List<String[]> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(new String[]{fin(4), "1"});
        deposit(token, depositBody(tranId("DDDDDDDD9"), PASS_PHRASE, many.toArray(new String[0][]))).andExpect(jsonPath("$.rsp_code").value("A0001"));
        deposit(token, depositBody(tranId("DDDDDDDDA"), PASS_PHRASE, new String[]{fin(4), "0"})).andExpect(jsonPath("$.rsp_code").value("A0001"));
        mvc.perform(post(DEPOSIT).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rsp_code").value("A0001"));

        assertThat(balance(4)).isEqualTo(vipBefore);
        // 형식 오류·암호문구 오류는 거래고유번호를 소모하지 않는다
        deposit(token, depositBody(tranId("DDDDDDDD4"), PASS_PHRASE, new String[]{fin(4), "1000"})).andExpect(jsonPath("$.rsp_code").value("A0000"));
    }

    @Test
    void depositIsExactlyOncePerTranId() throws Exception {
        String token = orgToken();
        long vipBefore = balance(4);

        deposit(token, depositBody(tranId("DDDDDDDDB"), PASS_PHRASE, new String[]{fin(4), "1000"})).andExpect(jsonPath("$.rsp_code").value("A0000"));
        deposit(token, depositBody(tranId("DDDDDDDDB"), PASS_PHRASE, new String[]{fin(4), "1000"})).andExpect(jsonPath("$.rsp_code").value("A0002"));

        assertThat(balance(4)).isEqualTo(vipBefore + 1000);
    }

    @Test
    void depositedMoneyShowsUpInReceiversTransactionHistory() throws Exception {
        long before = transactions.count();
        deposit(orgToken(), depositBody(tranId("DDDDDDDDC"), PASS_PHRASE, new String[]{fin(4), "12345"})).andExpect(jsonPath("$.rsp_code").value("A0000"));

        // 약정계좌 출금 한 건 + 수취 계좌 입금 한 건
        assertThat(transactions.count()).isEqualTo(before + 2);
        var received = transactions.findAll().stream().filter(t -> tranId("DDDDDDDDC").equals(t.getBankTranId())).toList();
        assertThat(received).extracting(t -> t.getTranType()).containsOnly("입금이체");
        assertThat(received).filteredOn(t -> t.getAccountId() == 4).singleElement().satisfies(t -> {
            assertThat(t.getTranAmt()).isEqualTo(12_345);
            assertThat(t.getAfterBalanceAmt()).isEqualTo(balance(4));
            assertThat(t.getPrintContent()).isEqualTo("정산금");
        });
        assertThat(received).filteredOn(t -> t.getAccountId() == 1).singleElement().satisfies(t -> assertThat(t.getPrintContent()).isEqualTo("BastionPay 정산"));
    }

    // ---- 입금이체(계좌번호 방식) ----

    private Map<String, Object> acntBody(String tranId, String[]... items) {
        Map<String, Object> body = depositBody(tranId, PASS_PHRASE, new String[]{fin(4), "1"});   // 헤더만 재사용
        List<Map<String, Object>> list = new ArrayList<>();
        int no = 1;
        for (String[] item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tran_no", no++);
            m.put("bank_code_std", item[0]);
            m.put("account_num", item[1]);
            m.put("print_content", "계좌번호 이체");
            m.put("tran_amt", item[2]);
            list.add(m);
        }
        body.put("req_cnt", list.size());
        body.put("req_list", list);
        return body;
    }

    private ResultActions depositByAccount(String token, Map<String, Object> body) throws Exception {
        var req = post(DEPOSIT_ACNT).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    @Test
    void depositByAccountNumberFindsReceiverByBankAndNumber() throws Exception {
        long vipBefore = balance(4);

        // vip 의 시드 계좌: 한빛은행(901) 2003456789012
        depositByAccount(orgToken(), acntBody(tranId("AAAAAAAA1"),
                new String[]{"901", "2003456789012", "5000"},          // 있는 계좌
                new String[]{"901", "9999999999999", "1000"},          // 없는 번호
                new String[]{"902", "2003456789012", "1000"}))         // 번호는 같지만 다른 은행 → 없음
                .andExpect(jsonPath("$.rsp_code").value("A0007"))
                .andExpect(jsonPath("$.res_list[0].rsp_code").value("A0000"))
                .andExpect(jsonPath("$.res_list[0].account_num_masked").value("200-***-9012"))
                .andExpect(jsonPath("$.res_list[0].account_holder_name").value("브***피"))
                .andExpect(jsonPath("$.res_list[0].fintech_use_num").doesNotExist())        // 이 방식은 핀테크번호를 돌려주지 않는다
                .andExpect(jsonPath("$.res_list[1].rsp_code").value("A0005"))
                .andExpect(jsonPath("$.res_list[2].rsp_code").value("A0005"));
        assertThat(balance(4)).isEqualTo(vipBefore + 5_000);
    }

    @Test
    void depositByAccountNumberValidatesFormatAndForbidsMixingForms() throws Exception {
        String token = orgToken();
        long vipBefore = balance(4);

        depositByAccount(token, acntBody(tranId("AAAAAAAA2"), new String[]{"90", "2003456789012", "1000"})).andExpect(jsonPath("$.rsp_code").value("A0001"));
        depositByAccount(token, acntBody(tranId("AAAAAAAA3"), new String[]{"901", "20034-56789", "1000"})).andExpect(jsonPath("$.rsp_code").value("A0001"));
        depositByAccount(token, acntBody(tranId("AAAAAAAA4"), new String[]{"901", "123", "1000"})).andExpect(jsonPath("$.rsp_code").value("A0001"));
        // 핀테크번호를 계좌번호 API 에 넣거나, 계좌번호를 핀테크번호 API 에 넣을 수 없다
        deposit(token, acntBody(tranId("AAAAAAAA5"), new String[]{"901", "2003456789012", "1000"})).andExpect(jsonPath("$.rsp_code").value("A0001"));
        depositByAccount(token, depositBody(tranId("AAAAAAAA6"), PASS_PHRASE, new String[]{fin(4), "1000"})).andExpect(jsonPath("$.rsp_code").value("A0001"));
        // 사용자 토큰과 잘못된 암호문구는 이 API 에서도 거절
        depositByAccount(transferToken(), acntBody(tranId("AAAAAAAA7"), new String[]{"901", "2003456789012", "1000"})).andExpect(status().isForbidden());
        Map<String, Object> wrongPass = acntBody(tranId("AAAAAAAA8"), new String[]{"901", "2003456789012", "1000"});
        wrongPass.put("wd_pass_phrase", "nope");
        depositByAccount(token, wrongPass).andExpect(jsonPath("$.rsp_code").value("A0009"));
        assertThat(balance(4)).isEqualTo(vipBefore);
    }
}
