package com.san.bastionpay.app;

import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 대시보드의 연결 전/후 화면. 오픈뱅킹 서비스는 대체하고, 로컬 MySQL 이 떠 있어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardTest {

    private static final long MEMBER_ID = 1L;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OpenBankingService openBanking;

    private static RequestPostProcessor user() {
        AuthMember m = new AuthMember(MEMBER_ID, "테스터", "USER", "STANDARD");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(m, null, m.authorities()));
    }

    private String dashboard() throws Exception {
        return mvc.perform(get("/dashboard").with(user())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void notLinkedShowsConnectGuideAndNoSampleData() throws Exception {
        when(openBanking.isConfigured()).thenReturn(true);
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(false);

        String html = dashboard();

        assertThat(html).contains("연결된 계좌가 없습니다", "href=\"/openbanking/authorize\"");
        // 전체 메뉴 설명에도 "자산 현황"이 있어 섹션 제목(id)으로 확인한다
        assertThat(html).doesNotContain("id=\"h-assets\"", "37,210,700", "예시 데이터");
    }

    @Test
    void notLinkedAndNotConfiguredDoesNotOfferToStartAuth() throws Exception {
        when(openBanking.isConfigured()).thenReturn(false);
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(false);

        assertThat(dashboard()).doesNotContain("/openbanking/authorize").contains("연결 안내 보기");
    }

    @Test
    void linkedShowsRealTotals() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(true);
        when(openBanking.summary(MEMBER_ID)).thenReturn(new OpenBankingService.Summary(2, 1_234_567L, 0));

        String html = dashboard();

        assertThat(html).contains("id=\"h-assets\"", "2개 계좌", "1,234,567원", "총 자산");
        assertThat(html).doesNotContain("연결된 계좌가 없습니다", "37,210,700", "예시 데이터", "가상계좌</strong>");
    }

    @Test
    void linkedWithoutOrgCodeShowsCountOnly() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(true);
        when(openBanking.summary(MEMBER_ID)).thenReturn(new OpenBankingService.Summary(3, null, 0));

        assertThat(dashboard()).contains("3개 계좌", "OPENBANKING_ORG_CODE").doesNotContain("총 자산");
    }

    @Test
    void partialBalanceFailureIsReported() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(true);
        when(openBanking.summary(MEMBER_ID)).thenReturn(new OpenBankingService.Summary(3, 500L, 1));

        assertThat(dashboard()).contains("500원", "1개 계좌의 잔액을 불러오지 못해");
    }

    @Test
    void apiFailureKeepsPageWithErrorMessage() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(true);
        when(openBanking.summary(MEMBER_ID)).thenThrow(new BusinessException(ErrorCode.OPENBANKING_API_ERROR, "boom"));

        assertThat(dashboard()).contains(ErrorCode.OPENBANKING_API_ERROR.message()).doesNotContain("boom");
    }

    @Test
    void expiredLinkFallsBackToNotLinked() throws Exception {
        when(openBanking.isLinked(MEMBER_ID)).thenReturn(true);
        when(openBanking.summary(MEMBER_ID)).thenThrow(new BusinessException(ErrorCode.OPENBANKING_NOT_LINKED));

        assertThat(dashboard()).contains("연결된 계좌가 없습니다");
    }
}
