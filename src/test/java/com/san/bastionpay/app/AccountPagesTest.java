package com.san.bastionpay.app;

import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingClient.Account;
import com.san.bastionpay.openbanking.OpenBankingClient.Transaction;
import com.san.bastionpay.openbanking.OpenBankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 연결한 계좌의 목록·상세·거래내역 화면. 오픈뱅킹 서비스는 대체하고, 로컬 MySQL 이 떠 있어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountPagesTest {

    private static final long MEMBER_ID = 1L;
    private static final String FIN_A = "199900000000000000000002";
    private static final String FIN_B = "199900000000000000000003";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OpenBankingService openBanking;

    private static RequestPostProcessor user() {
        AuthMember m = new AuthMember(MEMBER_ID, "테스터", "USER", "STANDARD");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(m, null, m.authorities()));
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path).with(user())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static Account account(String fin, String alias, String bank, String masked) {
        return new Account(fin, alias, bank, masked, "홍*동");
    }

    private static Transaction tx(String memo, boolean deposit, long amount, long after) {
        return new Transaction(LocalDateTime.of(2026, 10, 20, 9, 30), deposit, deposit ? "이체" : "카드", memo, amount, after);
    }

    @BeforeEach
    void linked() {
        when(openBanking.isConfigured()).thenReturn(true);
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(true);
    }

    @Test
    void notLinkedPagesShowConnectGuideInsteadOfData() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(false);

        for (String path : new String[]{"/accounts", "/transactions", "/accounts/" + FIN_A}) {
            String html = page(path);
            assertThat(html).as(path).contains("연결된 계좌가 없습니다", "href=\"/openbanking/authorize\"");
            assertThat(html).as(path).doesNotContain("예시 데이터", "한빛은행 급여 통장", "3,284,500");
        }
    }

    @Test
    void accountListShowsRealAccountsWithBalancesAndTotal() throws Exception {
        when(openBanking.accountsWithBalance(MEMBER_ID)).thenReturn(List.of(
                new OpenBankingService.AccountBalance(account(FIN_A, "월급 통장", "한빛은행", "100-***-8901"), 2_350_000L),
                new OpenBankingService.AccountBalance(account(FIN_B, "생활비", "새솔은행", "110-***-5610"), 870_000L)));

        String html = page("/accounts");

        assertThat(html).contains("한빛은행 월급 통장", "100-***-8901", "2,350,000원", "870,000원", "합계", "3,220,000원");
        assertThat(html).contains("href=\"/accounts/" + FIN_A + "\"", "2개");
        assertThat(html).doesNotContain("예시 데이터");
    }

    @Test
    void accountListHidesTotalWhenSomeBalanceIsMissing() throws Exception {
        when(openBanking.accountsWithBalance(MEMBER_ID)).thenReturn(List.of(
                new OpenBankingService.AccountBalance(account(FIN_A, "월급 통장", "한빛은행", "100-***-8901"), 2_350_000L),
                new OpenBankingService.AccountBalance(account(FIN_B, "생활비", "새솔은행", "110-***-5610"), null)));

        String html = page("/accounts");

        assertThat(html).contains("잔액 확인 불가", "합계를 표시하지 않았습니다").doesNotContain("class=\"row row--sum\"");
    }

    @Test
    void accountDetailShowsBalanceTransactionsAndPaging() throws Exception {
        var account = account(FIN_A, "월급 통장", "한빛은행", "100-***-8901");
        when(openBanking.history(MEMBER_ID, FIN_A, 30, "all", 2)).thenReturn(new OpenBankingService.AccountHistory(
                account, 2_350_000L, List.of(tx("월세", false, 700_000, 2_350_000), tx("용돈", true, 50_000, 3_050_000)), 2, true, 45));

        String html = page("/accounts/" + FIN_A + "?page=2");

        assertThat(html).contains("한빛은행 월급 통장", "100-***-8901", "2,350,000원", "45건");
        assertThat(html).contains("2026.10.20 09:30", "월세", "−700,000원", "+50,000원", "3,050,000원");
        // 2쪽이므로 이전·다음 링크가 모두 있고 조회 조건이 유지된다
        assertThat(html).contains("href=\"/accounts/" + FIN_A + "?days=30&amp;kind=all&amp;page=1\"", "href=\"/accounts/" + FIN_A + "?days=30&amp;kind=all&amp;page=3\"");
        assertThat(html).contains("aria-current=\"page\"", "aria-label=\"5페이지\"").doesNotContain("이 계좌에서 이체");
    }

    @Test
    void accountDetailPassesFiltersAndFallsBackForUnknownValues() throws Exception {
        var account = account(FIN_A, "월급 통장", "한빛은행", "100-***-8901");
        when(openBanking.history(anyLong(), any(), anyInt(), any(), anyInt())).thenReturn(
                new OpenBankingService.AccountHistory(account, 1L, List.of(), 1, false, 0));

        page("/accounts/" + FIN_A + "?days=90&kind=in");
        verify(openBanking).history(MEMBER_ID, FIN_A, 90, "in", 1);
        page("/accounts/" + FIN_A + "?days=9999&kind=zz&page=-5");
        verify(openBanking).history(MEMBER_ID, FIN_A, 30, "all", 1);
        assertThat(page("/accounts/" + FIN_A)).contains("조건에 맞는 거래가 없습니다");
    }

    @Test
    void otherPeoplesAccountNumberGivesNotFound() throws Exception {
        when(openBanking.history(anyLong(), any(), anyInt(), any(), anyInt()))
                .thenThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "본인 계좌 목록에 없는 번호"));

        mvc.perform(get("/accounts/199900000000000000000004").with(user())).andExpect(status().isNotFound());
        // 24자리 숫자가 아닌 경로는 서비스까지 가지 않는다
        mvc.perform(get("/accounts/a1").with(user())).andExpect(status().isNotFound());
    }

    @Test
    void transactionsPageListsRowsWithAccountAndTruncationNotice() throws Exception {
        when(openBanking.accountList(MEMBER_ID)).thenReturn(List.of(
                account(FIN_A, "월급 통장", "한빛은행", "100-***-8901"), account(FIN_B, "생활비", "새솔은행", "110-***-5610")));
        when(openBanking.allTransactions(MEMBER_ID, null, 30, "all")).thenReturn(new OpenBankingService.Transactions(List.of(
                new OpenBankingService.TransactionRow("한빛은행 월급 통장", tx("월세", false, 700_000, 1)),
                new OpenBankingService.TransactionRow("새솔은행 생활비", tx("용돈", true, 50_000, 2))), true, 1));

        String html = page("/transactions");

        assertThat(html).contains("전체 계좌", "한빛은행 월급 통장", "새솔은행 생활비", "−700,000원", "+50,000원");
        assertThat(html).contains("value=\"" + FIN_A + "\"", "최근 100건까지만", "1개 계좌의 거래내역을 불러오지 못해");
        assertThat(html).doesNotContain("예시 데이터");
    }

    @Test
    void transactionsPageFiltersByAccountAndIgnoresUnknownAccount() throws Exception {
        when(openBanking.accountList(MEMBER_ID)).thenReturn(List.of(account(FIN_A, "월급 통장", "한빛은행", "100-***-8901")));
        when(openBanking.allTransactions(anyLong(), any(), anyInt(), any())).thenReturn(new OpenBankingService.Transactions(List.of(), false, 0));

        page("/transactions?account=" + FIN_A + "&days=90&kind=out");
        verify(openBanking).allTransactions(MEMBER_ID, FIN_A, 90, "out");
        // 본인 목록에 없는 값(남의 번호 포함)은 전체로 취급한다
        String html = page("/transactions?account=199900000000000000000004&days=9999&kind=zz");
        verify(openBanking).allTransactions(MEMBER_ID, null, 30, "all");
        assertThat(html).contains("전체 계좌", "조건에 맞는 거래가 없습니다");
    }

    @Test
    void apiFailureKeepsPageWithErrorMessageAndNoInternalDetail() throws Exception {
        when(openBanking.accountsWithBalance(MEMBER_ID))
                .thenThrow(new BusinessException(ErrorCode.OPENBANKING_API_ERROR, "boom: secret detail"));

        assertThat(page("/accounts")).contains(ErrorCode.OPENBANKING_API_ERROR.message()).doesNotContain("boom");
    }

    @Test
    void expiredLinkFallsBackToConnectGuide() throws Exception {
        when(openBanking.accountsWithBalance(eq(MEMBER_ID))).thenThrow(new BusinessException(ErrorCode.OPENBANKING_NOT_LINKED));

        assertThat(page("/accounts")).contains("연결된 계좌가 없습니다");
    }

    @Test
    void connectPageDoesNotClaimToShowSampleData() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(false);

        String html = page("/connect");

        assertThat(html).contains("오픈뱅킹 계좌 연결").doesNotContain("예시 데이터");
    }

    @Test
    void connectPageShowsBalancesDirectlyAndLinksEachAccountToItsTransactions() throws Exception {
        when(openBanking.canQueryBalance()).thenReturn(true);
        when(openBanking.accountsWithBalance(MEMBER_ID)).thenReturn(List.of(
                new OpenBankingService.AccountBalance(account(FIN_A, "월급 통장", "한빛은행", "100-***-8901"), 2_350_000L),
                new OpenBankingService.AccountBalance(account(FIN_B, "생활비", "새솔은행", "110-***-5610"), null)));

        String html = page("/connect");

        assertThat(html).contains("한빛은행 월급 통장", "2,350,000원", "잔액 확인 불가", "href=\"/accounts/" + FIN_A + "\"", "href=\"/accounts/" + FIN_B + "\"",
                "계좌를 누르면 거래내역을 볼 수 있습니다", "연결 해제");
        assertThat(html).doesNotContain("잔액 조회</button>", "예시 데이터");
    }

    @Test
    void transactionsPageShowsTenPerPageWithPagerAndKeepsFilters() throws Exception {
        when(openBanking.accountList(MEMBER_ID)).thenReturn(List.of(account(FIN_A, "월급 통장", "한빛은행", "100-***-8901")));
        List<OpenBankingService.TransactionRow> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 25; i++) rows.add(new OpenBankingService.TransactionRow("한빛은행 월급 통장", tx("거래" + i, i % 2 == 0, 1_000L + i, 1)));
        when(openBanking.allTransactions(MEMBER_ID, null, 30, "all")).thenReturn(new OpenBankingService.Transactions(rows, false, 0));

        String second = page("/transactions?page=2");

        assertThat(second).contains("거래11", "거래20").doesNotContain("거래10<", "거래21<");
        assertThat(second).contains("aria-label=\"3페이지\"", "rel=\"prev\"", "rel=\"next\"", "page=1", "page=3", "days=30", "kind=all", "account=all");
        // 마지막 쪽, 범위를 벗어난 쪽(마지막 쪽으로)
        assertThat(page("/transactions?page=3")).contains("거래21", "거래25").doesNotContain("rel=\"next\"");
        assertThat(page("/transactions?page=99")).contains("거래21");
        assertThat(page("/transactions?page=0")).contains("거래1<").doesNotContain("rel=\"prev\"");
    }

    @Test
    void shortTransactionListHasNoPager() throws Exception {
        when(openBanking.accountList(MEMBER_ID)).thenReturn(List.of(account(FIN_A, "월급 통장", "한빛은행", "100-***-8901")));
        when(openBanking.allTransactions(MEMBER_ID, null, 30, "all")).thenReturn(new OpenBankingService.Transactions(
                List.of(new OpenBankingService.TransactionRow("한빛은행 월급 통장", tx("월세", false, 1, 1))), false, 0));

        assertThat(page("/transactions")).contains("월세").doesNotContain("aria-label=\"페이지 이동\"");
    }
}
