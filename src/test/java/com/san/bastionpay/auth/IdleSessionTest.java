package com.san.bastionpay.auth;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마지막 활동 후 30분이 지나면 다시 로그인하는 세션. 실제 로그인으로 받은 쿠키로 요청한다.
 * 로컬 MySQL 과 db/seed/member.sql 의 더미 회원(test@test.com)이 필요하고, 바꾼 데이터는 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class IdleSessionTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired SessionProperties sessionProps;
    @Autowired JwtProperties jwtProps;
    @Autowired JwtService jwtService;

    private Cookie access;
    private Cookie refresh;
    private String sessionId;

    @BeforeEach
    void login() throws Exception {
        MvcResult result = mvc.perform(post("/login").param("email", "test@test.com").param("password", "12345678A!").with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        access = result.getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
        refresh = result.getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
        assertThat(access).isNotNull();
        sessionId = jwtService.parseSession(access.getValue()).orElseThrow().sessionId();
    }

    private void idleExpiresIn(Duration fromNow) {
        em.flush();
        jdbc.update("update login_session set idle_expires_at = ? where session_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).plus(fromNow), sessionId);
        em.clear();
    }

    private long remainingSecondsInDb() {
        em.flush();
        LocalDateTime idle = jdbc.queryForObject("select idle_expires_at from login_session where session_id = ?", LocalDateTime.class, sessionId);
        return Duration.between(LocalDateTime.now(ZoneOffset.UTC), idle).toSeconds();
    }

    private String endReason() {
        em.flush();
        List<String> r = jdbc.queryForList("select end_reason from login_session where session_id = ? and ended_at is not null", String.class, sessionId);
        return r.isEmpty() ? null : r.getFirst();
    }

    @Test
    void loginStartsAThirtyMinuteSession() {
        assertThat(sessionProps.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(remainingSecondsInDb()).isBetween(29L * 60, 30L * 60);
        assertThat(jwtProps.refreshTokenTtl()).as("활동과 상관없는 로그인 최대 유지 시간").isGreaterThan(sessionProps.idleTimeout());
    }

    @Test
    void usingThePageExtendsTheSessionAndTheHeaderShowsTheTimer() throws Exception {
        idleExpiresIn(Duration.ofMinutes(10));

        String html = mvc.perform(get("/dashboard").cookie(access, refresh)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(remainingSecondsInDb()).as("활동하면 30분으로 다시 늘어난다").isBetween(29L * 60, 30L * 60);
        assertThat(html).contains("자동 로그아웃", "data-session-remaining=\"1800\"", "action=\"/session/extend\"", "연장");
    }

    @Test
    void askingTheRemainingTimeIsNotActivity() throws Exception {
        idleExpiresIn(Duration.ofMinutes(10));

        String body = mvc.perform(get("/session/status").cookie(access, refresh).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"remainingSeconds\":");
        long reported = Long.parseLong(body.replaceAll(".*\"remainingSeconds\":(\\d+).*", "$1"));
        assertThat(reported).isBetween(9L * 60, 10L * 60);
        assertThat(remainingSecondsInDb()).as("조회만으로는 늘어나지 않는다").isLessThanOrEqualTo(10L * 60);
    }

    @Test
    void extendButtonResetsTheClockToThirtyMinutes() throws Exception {
        idleExpiresIn(Duration.ofMinutes(2));

        String body = mvc.perform(post("/session/extend").cookie(access, refresh).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        long reported = Long.parseLong(body.replaceAll(".*\"remainingSeconds\":(\\d+).*", "$1"));
        assertThat(reported).isBetween(29L * 60, 30L * 60);
        assertThat(remainingSecondsInDb()).isBetween(29L * 60, 30L * 60);
    }

    @Test
    void extendNeedsALoginAndACsrfToken() throws Exception {
        mvc.perform(post("/session/extend").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/session/extend").cookie(access, refresh)).andExpect(status().isForbidden());
        mvc.perform(get("/session/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void afterThirtyIdleMinutesThePageSendsYouToLoginAndTheSessionIsDead() throws Exception {
        idleExpiresIn(Duration.ofSeconds(-1));   // 마지막 활동 후 30분이 지난 것처럼

        MvcResult result = mvc.perform(get("/dashboard").cookie(access, refresh).header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?expired")).andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).anyMatch(c -> c.startsWith("access_token=") && c.contains("Max-Age=0"));
        assertThat(endReason()).isEqualTo("IDLE_TIMEOUT");
        // Access Token 의 유효 기간이 남아 있어도, Refresh Token 이 있어도 되살릴 수 없다
        mvc.perform(get("/dashboard").cookie(access, refresh).header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/session/status").cookie(access, refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginPageExplainsWhyYouWereLoggedOut() throws Exception {
        String html = mvc.perform(get("/login").param("expired", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("활동이 없어 로그아웃되었습니다");
    }

    @Test
    void expiredSessionCannotBeRevivedWithTheRefreshTokenAlone() throws Exception {
        idleExpiresIn(Duration.ofSeconds(-1));

        MvcResult result = mvc.perform(get("/dashboard").cookie(refresh).header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE))
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith("access_token=") && !c.contains("Max-Age=0"));
        assertThat(endReason()).isEqualTo("IDLE_TIMEOUT");
    }

    @Test
    void anActiveSessionStillRenewsItsAccessTokenFromTheRefreshToken() throws Exception {
        MvcResult result = mvc.perform(get("/dashboard").cookie(refresh)).andExpect(status().isOk()).andReturn();   // Access Token 쿠키가 만료된 경우

        Cookie renewed = result.getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
        assertThat(renewed).isNotNull();
        assertThat(jwtService.parseSession(renewed.getValue()).orElseThrow().sessionId()).isEqualTo(sessionId);   // 같은 세션이 이어진다
        assertThat(endReason()).isNull();
    }

    @Test
    void logoutEndsTheSessionAtOnceEvenIfTheAccessTokenIsStillValid() throws Exception {
        mvc.perform(post("/logout").cookie(access, refresh).with(csrf())).andExpect(status().is3xxRedirection());

        assertThat(endReason()).isEqualTo("LOGOUT");
        mvc.perform(get("/session/status").cookie(access)).andExpect(status().isUnauthorized());   // 훔친 Access Token 으로도 못 쓴다
    }
}
