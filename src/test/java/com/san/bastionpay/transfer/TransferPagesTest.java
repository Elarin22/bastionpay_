package com.san.bastionpay.transfer;

import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingClient.Account;
import com.san.bastionpay.openbanking.OpenBankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이체 화면(입력 → 확인 → 실행). 오픈뱅킹·이체 서비스는 대체하고, 로컬 MySQL 이 떠 있어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
class TransferPagesTest {

    private static final long MEMBER_ID = 1L;
    private static final String FIN = "199900000000000000000002";
    private static final String FIN_NO_TRANSFER = "199900000000000000000003";
    private static final String REQUEST = "22222222-2222-2222-2222-222222222222";

    @Autowired MockMvc mvc;
    @MockitoBean OpenBankingService openBanking;
    @MockitoBean TransferService transfers;

    private static RequestPostProcessor user() {
        AuthMember m = new AuthMember(MEMBER_ID, "테스터", "USER", "STANDARD");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(m, null, m.authorities()));
    }

    private static Account account(String fin, String alias, boolean transferAgree) {
        return new Account(fin, alias, "한빛은행", "100-***-8901", "홍*동", transferAgree);
    }

    private String body(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.with(user()).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder form(String path, String from, String bank, String account, String amount, String memo) {
        return post(path).param("fromAccountId", from).param("toBank", bank).param("toAccount", account).param("amount", amount)
                .param("memo", memo).param("requestId", REQUEST);
    }

    @BeforeEach
    void linked() {
        when(openBanking.isConfigured()).thenReturn(true);
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(true);
        when(openBanking.canTransfer()).thenReturn(true);
        when(openBanking.accountsWithBalance(MEMBER_ID)).thenReturn(List.of(
                new OpenBankingService.AccountBalance(account(FIN, "월급 통장", true), 2_350_000L),
                new OpenBankingService.AccountBalance(account(FIN_NO_TRANSFER, "생활비", false), 870_000L)));
    }

    @Test
    void formListsOnlyAccountsWhereTransferIsAllowed() throws Exception {
        String html = body(get("/transfer"));

        assertThat(html).contains("value=\"" + FIN + "\"", "월급 통장", "2,350,000원", "한빛은행", "새솔은행");
        assertThat(html).contains("1회 이체 한도 2,000,000원 (STANDARD)", "VIP까지");   // 내 등급과 한도, 다음 등급까지 남은 금액
        assertThat(html).doesNotContain(FIN_NO_TRANSFER, "예시 데이터", "푸른저축은행");
    }

    @Test
    void notLinkedShowsConnectGuide() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(false);

        assertThat(body(get("/transfer"))).contains("연결된 계좌가 없습니다", "href=\"/openbanking/authorize\"").doesNotContain("다음: 내용 확인");
    }

    @Test
    void withoutTransferSettingsOrAllowedAccountsTheFormIsReplacedByGuidance() throws Exception {
        when(openBanking.canTransfer()).thenReturn(false);
        assertThat(body(get("/transfer"))).contains("OPENBANKING_PASS_PHRASE").doesNotContain("다음: 내용 확인");

        when(openBanking.canTransfer()).thenReturn(true);
        when(openBanking.accountsWithBalance(MEMBER_ID)).thenReturn(List.of(
                new OpenBankingService.AccountBalance(account(FIN_NO_TRANSFER, "생활비", false), 870_000L)));
        assertThat(body(get("/transfer"))).contains("이체가 허용된 계좌가 없습니다", "출금이체도 허용").doesNotContain("다음: 내용 확인");
    }

    @Test
    void confirmShowsSummaryWithMaskedReceiverAndBalanceAfter() throws Exception {
        String html = body(form("/transfer/confirm", FIN, "한빛은행", "200-3456-789012", "100000", "용돈"));

        assertThat(html).contains("이체하기", "한빛은행 월급 통장", "100,000원", "2,250,000원", "용돈");
        // 화면에 보이는 요약에는 뒤 4자리만 나온다(확인→실행 단계로 값을 넘기는 숨은 입력란에는 원문이 있고, 실행 단계에서 서버가 다시 검증한다)
        assertThat(html).contains("<dd>한빛은행 *********9012</dd>");
        assertThat(html).containsPattern("name=\"requestId\" value=\"[0-9a-f-]{36}\"");
        verify(transfers, never()).transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void confirmRejectsBadInputWithoutCallingTheBank() throws Exception {
        // 1회 이체 한도는 등급마다 다르다(이 사용자는 DB 에 없어 가장 낮은 등급: 200만원)
        assertThat(body(form("/transfer/confirm", FIN, "한빛은행", "2003456789012", "2000001", ""))).contains("1회 이체 한도(2,000,000원)").doesNotContain("이체하기</span>");
        assertThat(body(form("/transfer/confirm", FIN, "한빛은행", "2003456789012", "10000001", ""))).contains("1회 이체 한도");
        when(openBanking.accountsWithBalance(MEMBER_ID)).thenReturn(List.of(new OpenBankingService.AccountBalance(account(FIN, "월급 통장", true), 1_000_000L)));
        assertThat(body(form("/transfer/confirm", FIN, "한빛은행", "2003456789012", "1500000", ""))).contains("잔액이 부족합니다").doesNotContain("이체하기</span>");
        when(openBanking.accountsWithBalance(MEMBER_ID)).thenReturn(List.of(
                new OpenBankingService.AccountBalance(account(FIN, "월급 통장", true), 2_350_000L),
                new OpenBankingService.AccountBalance(account(FIN_NO_TRANSFER, "생활비", false), 870_000L)));
        assertThat(body(form("/transfer/confirm", FIN, "한빛은행", "2003456789012", "0", ""))).contains("1원 이상");
        assertThat(body(form("/transfer/confirm", FIN, "한빛은행", "2003456789012", "1,000", ""))).contains("숫자만");
        assertThat(body(form("/transfer/confirm", FIN, "푸른저축은행", "2003456789012", "1000", ""))).contains("받는 은행을 목록에서");
        assertThat(body(form("/transfer/confirm", FIN, "한빛은행", "1234", "1000", ""))).contains("계좌번호는");
        assertThat(body(form("/transfer/confirm", FIN, "한빛은행", "2003456789012", "1000", "가".repeat(21)))).contains("20자까지");
        // 이체가 허용되지 않은 계좌·내 계좌가 아닌 번호
        assertThat(body(form("/transfer/confirm", FIN_NO_TRANSFER, "한빛은행", "2003456789012", "1000", ""))).contains("출금 계좌를 목록에서");
        assertThat(body(form("/transfer/confirm", "199900000000000000000004", "한빛은행", "2003456789012", "1000", ""))).contains("출금 계좌를 목록에서");
        verify(transfers, never()).transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void executeCompletedShowsReceiverNameAndCurrentBalance() throws Exception {
        when(transfers.transfer(MEMBER_ID, REQUEST, FIN, "901", "2003456789012", 100_000, "용돈")).thenReturn(new TransferService.Outcome(
                TransferStatus.COMPLETED, REQUEST, 100_000, "한빛은행 월급 통장", "901", "200-***-9012", "브***피", null, false, FIN));
        when(openBanking.currentBalance(MEMBER_ID, FIN)).thenReturn(2_250_000L);

        String html = body(form("/transfer/execute", FIN, "한빛은행", "200-3456-789012", "100000", "용돈"));

        assertThat(html).contains("이체가 완료되었습니다.", "브***피", "100,000원", "2,250,000원", REQUEST, "거래내역 보기");
        assertThat(html).doesNotContain("이미 처리된");
    }

    @Test
    void executeRepeatedRequestSaysAlreadyProcessed() throws Exception {
        when(transfers.transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(new TransferService.Outcome(
                TransferStatus.COMPLETED, REQUEST, 100_000, "한빛은행 월급 통장", "901", "200-***-9012", "브***피", null, true, FIN));
        when(openBanking.currentBalance(anyLong(), anyString())).thenReturn(2_250_000L);

        assertThat(body(form("/transfer/execute", FIN, "한빛은행", "2003456789012", "100000", ""))).contains("이미 처리된 이체입니다.");
    }

    @Test
    void executeRefundedExplainsMoneyWasReturned() throws Exception {
        when(transfers.transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(new TransferService.Outcome(
                TransferStatus.REFUNDED, REQUEST, 100_000, "한빛은행 월급 통장", "901", "200-***-9012", null, "받는 계좌를 찾을 수 없습니다.", false, FIN));

        String html = body(form("/transfer/execute", FIN, "한빛은행", "2003456789012", "100000", ""));

        assertThat(html).contains("출금된 금액은 내 계좌로 돌려드렸습니다", "받는 계좌를 찾을 수 없습니다.", REQUEST).doesNotContain("이체가 완료되었습니다.");
    }

    @Test
    void executeNeedsReviewTellsNotToRetryAndShowsRequestId() throws Exception {
        when(transfers.transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(new TransferService.Outcome(
                TransferStatus.NEEDS_REVIEW, REQUEST, 100_000, "한빛은행 월급 통장", "901", "200-***-9012", null, null, false, FIN));

        String html = body(form("/transfer/execute", FIN, "한빛은행", "2003456789012", "100000", ""));

        assertThat(html).contains("이체 결과를 확인하고 있습니다.", "다시 시도하지 마세요", REQUEST).doesNotContain("이체가 완료되었습니다.");
    }

    @Test
    void executeBankRejectionShowsMessageAndKeepsInput() throws Exception {
        when(transfers.transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_BALANCE, "secret internal detail"));

        String html = body(form("/transfer/execute", FIN, "한빛은행", "2003456789012", "100000", "용돈"));

        assertThat(html).contains(ErrorCode.INSUFFICIENT_BALANCE.message(), "다음: 내용 확인", "2003456789012").doesNotContain("secret internal detail");
    }

    @Test
    void executeRevalidatesInputInsteadOfTrustingTheConfirmScreen() throws Exception {
        assertThat(body(form("/transfer/execute", FIN_NO_TRANSFER, "한빛은행", "2003456789012", "1000", ""))).contains("입력 내용을 다시 확인해 주세요.");
        assertThat(body(form("/transfer/execute", FIN, "한빛은행", "2003456789012", "1000000000000", ""))).contains("입력 내용을 다시 확인해 주세요.");   // 12자리(은행 API 의 상한)를 넘는다
        verify(transfers, never()).transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void postsWithoutCsrfTokenAreRejected() throws Exception {
        mvc.perform(form("/transfer/execute", FIN, "한빛은행", "2003456789012", "1000", "").with(user())).andExpect(status().isForbidden());
        verify(transfers, never()).transfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }
}
