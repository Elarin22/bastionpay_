package com.san.bastionpay.openbanking;

import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 state 검증, 토큰 저장, 계좌 소유 확인 규칙을 확인한다. */
class OpenBankingServiceTest {

    private OpenBankingProperties props;
    private OpenBankingClient client;
    private OpenBankingLinkRepository links;
    private OpenBankingAuthStateRepository states;
    private TokenCipher cipher;
    private OpenBankingService service;

    @BeforeEach
    void setUp() {
        props = new OpenBankingProperties("https://testapi.openbanking.or.kr", "cid", "csecret",
                "https://localhost:8080/openbanking/callback", "M202112345", "secret", Duration.ofMinutes(10),
                Duration.ofSeconds(3), Duration.ofSeconds(10), null);
        client = mock(OpenBankingClient.class);
        links = mock(OpenBankingLinkRepository.class);
        states = mock(OpenBankingAuthStateRepository.class);
        cipher = new TokenCipher(props);
        service = new OpenBankingService(props, client, links, states, cipher, mock(com.san.bastionpay.audit.SecurityEventRecorder.class));
    }

    @Test
    void beginBuildsAuthorizeUrlAndStoresOnlyStateHash() {
        OpenBankingService.Authorization auth = service.begin(7L);

        assertThat(auth.state()).hasSize(32).matches("[A-Za-z0-9]{32}");
        assertThat(auth.url()).startsWith("https://testapi.openbanking.or.kr/oauth/2.0/authorize?")
                .contains("response_type=code", "client_id=cid", "state=" + auth.state(), "auth_type=0",
                        "scope=login%20inquiry", "redirect_uri=https://localhost:8080/openbanking/callback")
                .doesNotContain("csecret");
        ArgumentCaptor<OpenBankingAuthState> saved = ArgumentCaptor.forClass(OpenBankingAuthState.class);
        verify(states).save(saved.capture());
        assertThat(saved.getValue().getStateHash()).hasSize(64).isNotEqualTo(auth.state());
        assertThat(saved.getValue().getMemberId()).isEqualTo(7L);
    }

    @Test
    void beginFailsWhenNotConfigured() {
        OpenBankingProperties blank = new OpenBankingProperties("b", "", "", "r", "", "secret", Duration.ofMinutes(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), null);
        OpenBankingService unconfigured = new OpenBankingService(blank, client, links, states, cipher, mock(com.san.bastionpay.audit.SecurityEventRecorder.class));

        assertThatThrownBy(() -> unconfigured.begin(1L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_NOT_CONFIGURED));
    }

    @Test
    void completeRejectsStateMismatchWithoutTouchingStoreOrApi() {
        assertThatThrownBy(() -> service.complete("aaa", "bbb", "code")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.complete("aaa", null, "code")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.complete("aaa", "aaa", "")).isInstanceOf(BusinessException.class);

        verify(states, never()).consume(anyString());
        verify(client, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void completeRejectsStateThatWasAlreadyConsumed() {
        when(states.findByStateHash(anyString())).thenReturn(
                Optional.of(new OpenBankingAuthState("h", 7L, LocalDateTime.now().plusMinutes(5))));
        when(states.consume(anyString())).thenReturn(0);   // 동시에 온 다른 요청이 먼저 사용함

        assertThatThrownBy(() -> service.complete("s", "s", "code")).isInstanceOf(BusinessException.class);
        verify(client, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void completeRejectsExpiredState() {
        when(states.findByStateHash(anyString())).thenReturn(
                Optional.of(new OpenBankingAuthState("h", 7L, LocalDateTime.now().minusSeconds(1))));
        when(states.consume(anyString())).thenReturn(1);

        assertThatThrownBy(() -> service.complete("s", "s", "code")).isInstanceOf(BusinessException.class);
        verify(client, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void completeStoresEncryptedTokenForTheMemberBoundToState() {
        when(states.findByStateHash(anyString())).thenReturn(
                Optional.of(new OpenBankingAuthState("h", 7L, LocalDateTime.now().plusMinutes(5))));
        when(states.consume(anyString())).thenReturn(1);
        when(links.findByMemberId(7L)).thenReturn(Optional.empty());
        when(links.findByUserSeqNo("110")).thenReturn(Optional.empty());
        when(client.exchangeCode(org.mockito.ArgumentMatchers.eq("code"), anyString())).thenReturn(new OpenBankingClient.Token("plain-token", 3600, "login inquiry", "110"));

        long memberId = service.complete("s", "s", "code");

        assertThat(memberId).isEqualTo(7L);
        ArgumentCaptor<OpenBankingLink> link = ArgumentCaptor.forClass(OpenBankingLink.class);
        verify(links).save(link.capture());
        assertThat(link.getValue().getAccessTokenEnc()).doesNotContain("plain-token");
        assertThat(cipher.decrypt(link.getValue().getAccessTokenEnc())).isEqualTo("plain-token");
        assertThat(link.getValue().getUserSeqNo()).isEqualTo("110");
    }

    @Test
    void completeRejectsBankCustomerAlreadyLinkedToAnotherMember() {
        when(states.findByStateHash(anyString())).thenReturn(
                Optional.of(new OpenBankingAuthState("h", 7L, LocalDateTime.now().plusMinutes(5))));
        when(states.consume(anyString())).thenReturn(1);
        when(client.exchangeCode(org.mockito.ArgumentMatchers.eq("code"), anyString())).thenReturn(new OpenBankingClient.Token("plain-token", 3600, "login inquiry", "110"));
        when(links.findByUserSeqNo("110")).thenReturn(Optional.of(
                new OpenBankingLink(8L, "110", "enc", "login", LocalDateTime.now().plusHours(1))));

        assertThatThrownBy(() -> service.complete("s", "s", "code"))
                .isInstanceOf(com.san.bastionpay.core.exception.BusinessException.class)
                .extracting(e -> ((com.san.bastionpay.core.exception.BusinessException) e).getErrorCode())
                .isEqualTo(com.san.bastionpay.core.exception.ErrorCode.OPENBANKING_ALREADY_LINKED);
        verify(links, never()).save(any());
    }

    @Test
    void completeAllowsRelinkingTheSameBankCustomerByTheSameMember() {
        when(states.findByStateHash(anyString())).thenReturn(
                Optional.of(new OpenBankingAuthState("h", 7L, LocalDateTime.now().plusMinutes(5))));
        when(states.consume(anyString())).thenReturn(1);
        OpenBankingLink mine = new OpenBankingLink(7L, "110", "enc", "login", LocalDateTime.now().plusHours(1));
        when(links.findByUserSeqNo("110")).thenReturn(Optional.of(mine));
        when(links.findByMemberId(7L)).thenReturn(Optional.of(mine));
        when(client.exchangeCode(org.mockito.ArgumentMatchers.eq("code"), anyString())).thenReturn(new OpenBankingClient.Token("plain-token", 3600, "login inquiry", "110"));

        assertThat(service.complete("s", "s", "code")).isEqualTo(7L);
        verify(links).save(mine);
    }

    @Test
    void expiredLinkCountsAsNotLinked() {
        OpenBankingLink link = new OpenBankingLink(7L, "110", cipher.encrypt("tok"), "login", LocalDateTime.now().minusSeconds(1));
        when(links.findByMemberId(7L)).thenReturn(Optional.of(link));

        assertThat(service.isLinked(7L)).isFalse();
        assertThatThrownBy(() -> service.accounts(7L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_NOT_LINKED));
    }

    @Test
    void summaryTotalsBalancesAndCountsFailures() {
        OpenBankingLink link = new OpenBankingLink(7L, "110", cipher.encrypt("tok"), "login inquiry", LocalDateTime.now().plusDays(1));
        when(links.findByMemberId(7L)).thenReturn(Optional.of(link));
        when(client.accounts("tok", "110")).thenReturn(List.of(
                new OpenBankingClient.Account("a", "x", "b", "m", "h"),
                new OpenBankingClient.Account("b", "x", "b", "m", "h"),
                new OpenBankingClient.Account("c", "x", "b", "m", "h")));
        when(client.balance("tok", "a")).thenReturn(new OpenBankingClient.Balance("b", 100, 100));
        when(client.balance("tok", "b")).thenThrow(new BusinessException(ErrorCode.OPENBANKING_API_ERROR, "fail"));
        when(client.balance("tok", "c")).thenReturn(new OpenBankingClient.Balance("b", 250, 250));

        OpenBankingService.Summary summary = service.summary(7L);

        assertThat(summary.accountCount()).isEqualTo(3);
        assertThat(summary.totalBalance()).isEqualTo(350L);
        assertThat(summary.failedCount()).isEqualTo(1);
    }

    @Test
    void summaryWithoutOrgCodeSkipsBalanceCalls() {
        OpenBankingProperties noOrg = new OpenBankingProperties("b", "c", "s", "r", "", "secret", Duration.ofMinutes(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), null);
        OpenBankingService svc = new OpenBankingService(noOrg, client, links, states, cipher, mock(com.san.bastionpay.audit.SecurityEventRecorder.class));
        OpenBankingLink link = new OpenBankingLink(7L, "110", cipher.encrypt("tok"), "login", LocalDateTime.now().plusDays(1));
        when(links.findByMemberId(7L)).thenReturn(Optional.of(link));
        when(client.accounts("tok", "110")).thenReturn(List.of(new OpenBankingClient.Account("a", "x", "b", "m", "h")));

        OpenBankingService.Summary summary = svc.summary(7L);

        assertThat(summary.accountCount()).isEqualTo(1);
        assertThat(summary.totalBalance()).isNull();
        verify(client, never()).balance(any(), any());
    }

    // ---- 거래내역 ----

    private void linkedWithAccounts(OpenBankingClient.Account... accounts) {
        OpenBankingLink link = new OpenBankingLink(7L, "110", cipher.encrypt("tok"), "login inquiry", LocalDateTime.now().plusDays(1));
        when(links.findByMemberId(7L)).thenReturn(Optional.of(link));
        when(client.accounts("tok", "110")).thenReturn(List.of(accounts));
    }

    private static OpenBankingClient.Account acct(String fin, String alias) {
        return new OpenBankingClient.Account(fin, alias, "한빛은행", "100-***-0000", "홍*동");
    }

    private static OpenBankingClient.Transaction tran(LocalDateTime at, String memo) {
        return new OpenBankingClient.Transaction(at, true, "이체", memo, 1_000, 5_000);
    }

    @Test
    void historyAsksTheApiForTheRightRangeTypeAndPage() {
        linkedWithAccounts(acct("a", "월급"));
        when(client.transactions(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new OpenBankingClient.TransactionPage(List.of(tran(LocalDateTime.now(), "x")), 41, true, 9_000));

        OpenBankingService.AccountHistory h = service.history(7L, "a", 30, "out", 3);

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        verify(client).transactions("tok", "a", today.minusDays(29), today, "O", 3, 10);
        assertThat(h.balance()).isEqualTo(9_000);
        assertThat(h.hasNext()).isTrue();
        assertThat(h.totalCount()).isEqualTo(41);
        assertThat(h.page()).isEqualTo(3);
    }

    @Test
    void historyAndAllTransactionsRejectAccountsNotOwned() {
        linkedWithAccounts(acct("mine", "월급"));

        assertThatThrownBy(() -> service.history(7L, "someone-elses", 30, "all", 1)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        assertThatThrownBy(() -> service.allTransactions(7L, "someone-elses", 30, "all")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        verify(client, never()).transactions(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void allTransactionsMergesAccountsNewestFirstAndCountsFailures() {
        linkedWithAccounts(acct("a", "월급"), acct("b", "생활비"), acct("c", "비상금"));
        LocalDateTime now = LocalDateTime.now();
        when(client.transactions(any(), org.mockito.ArgumentMatchers.eq("a"), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new OpenBankingClient.TransactionPage(List.of(tran(now.minusDays(3), "a-old"), tran(now.minusDays(1), "a-new")), 2, false, 0));
        when(client.transactions(any(), org.mockito.ArgumentMatchers.eq("b"), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new OpenBankingClient.TransactionPage(List.of(tran(now.minusDays(2), "b-mid")), 1, false, 0));
        when(client.transactions(any(), org.mockito.ArgumentMatchers.eq("c"), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new BusinessException(ErrorCode.OPENBANKING_API_ERROR, "fail"));

        OpenBankingService.Transactions result = service.allTransactions(7L, null, 30, "all");

        assertThat(result.rows()).extracting(r -> r.tx().content()).containsExactly("a-new", "b-mid", "a-old");
        assertThat(result.rows().get(1).accountLabel()).isEqualTo("한빛은행 생활비");
        assertThat(result.failedAccounts()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void allTransactionsCapsAtOneHundredAndFlagsTruncation() {
        linkedWithAccounts(acct("a", "월급"), acct("b", "생활비"));
        LocalDateTime now = LocalDateTime.now();
        for (String fin : new String[]{"a", "b"}) {
            List<OpenBankingClient.Transaction> items = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) items.add(tran(now.minusMinutes(i * 2 + (fin.equals("a") ? 0 : 1)), fin + i));
            when(client.transactions(any(), org.mockito.ArgumentMatchers.eq(fin), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new OpenBankingClient.TransactionPage(items, 100, false, 0));
        }

        OpenBankingService.Transactions result = service.allTransactions(7L, null, 90, "all");

        assertThat(result.rows()).hasSize(100);
        assertThat(result.truncated()).isTrue();
        assertThat(result.rows().get(0).tx().content()).isEqualTo("a0");   // 가장 최근
    }

    @Test
    void transactionQueriesNeedOrgCodeAndKindMapsToInquiryType() {
        OpenBankingProperties noOrg = new OpenBankingProperties("b", "c", "s", "r", "", "secret", Duration.ofMinutes(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), null);
        OpenBankingService svc = new OpenBankingService(noOrg, client, links, states, cipher, mock(com.san.bastionpay.audit.SecurityEventRecorder.class));

        assertThatThrownBy(() -> svc.history(7L, "a", 30, "all", 1)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENBANKING_NOT_CONFIGURED));
        assertThat(OpenBankingService.inquiryType("in")).isEqualTo("I");
        assertThat(OpenBankingService.inquiryType("out")).isEqualTo("O");
        assertThat(OpenBankingService.inquiryType("all")).isEqualTo("A");
        assertThat(OpenBankingService.inquiryType("zz")).isEqualTo("A");
        assertThat(OpenBankingService.inquiryType(null)).isEqualTo("A");
    }

    @Test
    void accountsWithBalanceKeepsAccountsWhenOneBalanceFails() {
        linkedWithAccounts(acct("a", "월급"), acct("b", "생활비"));
        when(client.balance("tok", "a")).thenReturn(new OpenBankingClient.Balance("한빛은행", 100, 100));
        when(client.balance("tok", "b")).thenThrow(new BusinessException(ErrorCode.OPENBANKING_API_ERROR, "fail"));

        List<OpenBankingService.AccountBalance> rows = service.accountsWithBalance(7L);

        assertThat(rows).extracting(OpenBankingService.AccountBalance::balance).containsExactly(100L, null);
    }

    @Test
    void historyShowsLastPageWhenRequestedPageIsOutOfRange() {
        linkedWithAccounts(acct("a", "월급"));
        when(client.transactions(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(9), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new OpenBankingClient.TransactionPage(List.of(), 25, false, 7_000));
        when(client.transactions(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new OpenBankingClient.TransactionPage(List.of(tran(LocalDateTime.now(), "last")), 25, false, 7_000));

        OpenBankingService.AccountHistory h = service.history(7L, "a", 90, "all", 9);

        assertThat(h.page()).isEqualTo(3);                      // 25건은 10건씩 3쪽
        assertThat(h.totalPages()).isEqualTo(3);
        assertThat(h.transactions()).extracting(OpenBankingClient.Transaction::content).containsExactly("last");
    }

    @Test
    void totalPagesRoundsUpAndIsAtLeastOne() {
        OpenBankingClient.Account a = acct("a", "x");
        assertThat(new OpenBankingService.AccountHistory(a, 0, List.of(), 1, false, 0).totalPages()).isEqualTo(1);
        assertThat(new OpenBankingService.AccountHistory(a, 0, List.of(), 1, false, 10).totalPages()).isEqualTo(1);
        assertThat(new OpenBankingService.AccountHistory(a, 0, List.of(), 1, true, 11).totalPages()).isEqualTo(2);
        assertThat(new OpenBankingService.AccountHistory(a, 0, List.of(), 1, true, 45).totalPages()).isEqualTo(5);
    }
}
