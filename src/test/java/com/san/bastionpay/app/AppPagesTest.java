package com.san.bastionpay.app;

import com.san.bastionpay.auth.AuthMember;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그인 후 화면의 접근 제어와 구성(사이드바, 대시보드 요약) 검증. 로컬 MySQL 이 떠 있어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AppPagesTest {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor user() {
        AuthMember m = new AuthMember(1L, "테스터", "USER", "STANDARD");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(m, null, m.authorities()));
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path).with(user())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyAppPageRequiresLogin() throws Exception {
        for (String path : new String[]{"/dashboard", "/accounts", "/accounts/a1", "/transactions", "/transfer", "/connect"}) {
            mvc.perform(get(path).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isFound()).andExpect(header().string("Location", "/login?required"));
        }
    }

    @Test
    void headerKeepsOnlyFrequentItemsAndMegaMenuHoldsEverything() throws Exception {
        String html = page("/dashboard");

        // 헤더 가운데 카테고리는 3개만
        String center = html.substring(html.indexOf("class=\"site-nav-center\""), html.indexOf("class=\"site-actions\""));
        assertThat(center).contains("대시보드", "이체", "거래내역").doesNotContain("오픈뱅킹");

        // 전체 메뉴에는 모든 항목이 카테고리별로 있다
        String mega = html.substring(html.indexOf("id=\"mega-menu\""));
        assertThat(mega).contains("이용", "자산", "연결", "입출금 계좌", "오픈뱅킹 연결", "로그아웃");
        assertThat(mega).contains("href=\"/accounts\"", "href=\"/connect\"", "href=\"/transfer\"", "href=\"/transactions\"");
        // 현재 페이지 표시, 사이드바는 더 이상 없다
        assertThat(mega).contains("<a href=\"/dashboard\" aria-current=\"page\">");
        assertThat(html).doesNotContain("class=\"sidebar\"");
    }

    @Test
    void megaMenuIsClosedByDefaultAndToggleIsAccessible() throws Exception {
        String html = page("/dashboard");

        assertThat(html).containsPattern("id=\"mega-menu\"[^>]*hidden");
        assertThat(html).contains("aria-controls=\"mega-menu\"", "aria-expanded=\"false\"", "aria-label=\"전체 메뉴\"");
    }

    @Test
    void hardcodedAssetPagesAreGone() throws Exception {
        // 예금·적금·가상계좌는 예시 데이터뿐이라 뺐다. 로그인해도 주소를 직접 입력하면 404, 메뉴에도 없다
        for (String path : new String[]{"/deposits", "/savings", "/virtual-accounts", "/virtual-accounts/v1"}) {
            mvc.perform(get(path).with(user()).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        }
        String html = page("/dashboard");
        String mega = html.substring(html.indexOf("id=\"mega-menu\""));
        assertThat(mega).doesNotContain("href=\"/deposits\"", "href=\"/savings\"", "href=\"/virtual-accounts\"",
                "mega__name\">예금<", "mega__name\">적금<", "mega__name\">가상계좌<");
    }

}
