package com.san.bastionpay.core.exception;

import com.san.bastionpay.auth.AuthMember;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 예외 → 응답 매핑 검증. 컨텍스트가 DB(Flyway/JPA)를 쓰므로 로컬 MySQL 이 떠 있어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorHandlingTest {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor loggedIn() {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthMember(1L, "테스터", "USER", "STANDARD"), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ---------- AuthenticationEntryPoint (401) ----------

    @Test
    void anonymousPageRequestRedirectsToLoginWithoutOriginalPath() throws Exception {
        mvc.perform(get("/account/secret").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?required"));
    }

    @Test
    void anonymousFetchRequestGets401WithoutBody() throws Exception {
        MvcResult result = mvc.perform(get("/account/secret").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(body(result)).isEmpty();
    }

    @Test
    void anonymousHtmxRequestGets401EvenIfBrowserAcceptsHtml() throws Exception {
        mvc.perform(get("/account/secret").accept(MediaType.TEXT_HTML).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginPageExplainsWhyAfterRedirect() throws Exception {
        MvcResult result = mvc.perform(get("/login").param("required", "")).andExpect(status().isOk()).andReturn();
        assertThat(body(result)).contains("로그인이 필요한 화면입니다");
    }

    // ---------- AccessDeniedHandler (403) ----------

    @Test
    void csrfFailureIsForbidden() throws Exception {
        mvc.perform(post("/signup/email").param("email", "a@example.com")).andExpect(status().isForbidden());
    }

    // ---------- GlobalExceptionHandler ----------

    @Test
    void businessExceptionRendersErrorCodeMessageAndHidesDetail() throws Exception {
        MvcResult result = mvc.perform(get("/test-errors/business").with(loggedIn()))
                .andExpect(status().isNotFound()).andReturn();
        String html = body(result);
        assertThat(html).contains("계좌를 찾을 수 없습니다", "404", "테스터");
        assertThat(html).doesNotContain("internal-detail-must-not-leak");
    }

    @Test
    void unexpectedExceptionIs500WithTraceIdAndNoInternals() throws Exception {
        MvcResult result = mvc.perform(get("/test-errors/boom").with(loggedIn()))
                .andExpect(status().isInternalServerError()).andReturn();
        String html = body(result);
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).isNotBlank();
        assertThat(html).contains("참조 번호", traceId);
        assertThat(html).doesNotContain("secret-internal-message", "IllegalStateException", "ThrowingTestController");
    }

    @Test
    void unknownPathForLoggedInUserIs404Page() throws Exception {
        MvcResult result = mvc.perform(get("/no/such/page").with(loggedIn())).andExpect(status().isNotFound()).andReturn();
        assertThat(body(result)).contains("주소가 바뀌었거나 없는 페이지입니다");
    }

    @Test
    void wrongMethodIs405Page() throws Exception {
        MvcResult result = mvc.perform(post("/test-errors/param").with(csrf()).with(loggedIn()))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertThat(body(result)).contains("405");
    }

    @Test
    void badParameterIs400Page() throws Exception {
        MvcResult result = mvc.perform(get("/test-errors/param").param("n", "abc").with(loggedIn()))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(body(result)).contains("400", "요청 내용을 확인한 뒤");
    }

    // ---------- 보안 예외는 삼키지 않고 Security 로 넘긴다 ----------

    @Test
    void accessDeniedFromControllerStays403NotWrapped500() throws Exception {
        mvc.perform(get("/test-errors/denied").with(loggedIn())).andExpect(status().isForbidden());
    }

    @Test
    void authenticationExceptionFromControllerGoesToEntryPoint() throws Exception {
        mvc.perform(get("/test-errors/unauthenticated").accept(MediaType.TEXT_HTML).with(loggedIn()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?required"));
    }
}
