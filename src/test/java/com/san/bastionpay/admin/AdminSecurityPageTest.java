package com.san.bastionpay.admin;

import com.san.bastionpay.audit.AccountLockout;
import com.san.bastionpay.audit.AccountLockoutRepository;
import com.san.bastionpay.audit.AuditLogRepository;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.LoginSubjectHasher;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventEntity;
import com.san.bastionpay.audit.SecurityEventRepository;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 보안 대시보드: 관리자만 볼 수 있는지, 집계가 화면에 나오는지, 잠금 해제가 감사 기록에 남는지. 테스트 데이터는 롤백된다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminSecurityPageTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired SecurityEventRepository events;
    @Autowired AccountLockoutRepository lockouts;
    @Autowired AuditLogRepository audits;
    @Autowired LoginSubjectHasher hasher;

    private RequestPostProcessor as(String role) {
        long id = members.findByEmail(role.equals("ADMIN") ? "admin@test.com" : "test@test.com").orElseThrow().getId();
        AuthMember m = new AuthMember(id, role.equals("ADMIN") ? "관리자" : "테스터", role, "STANDARD");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(m, null, m.authorities()));
    }

    @Test
    void onlyAdminsCanOpenTheDashboard() throws Exception {
        mvc.perform(get("/admin/security")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/security").with(as("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/security").with(as("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void dashboardShowsCountsFailuresByIpAndAccountWithoutRevealingTheEmail() throws Exception {
        String hash = hasher.hash("test@test.com");
        for (int i = 0; i < 3; i++) {
            events.save(new SecurityEventEntity(SecurityEvent.of(SecurityEventType.LOGIN_FAILURE, EventOutcome.FAILURE)
                    .sourceIp("203.0.113.50").loginSubjectHash(hash).reasonCode("BAD_CREDENTIALS").build()));
        }
        events.save(new SecurityEventEntity(SecurityEvent.of(SecurityEventType.ACCESS_DENIED, EventOutcome.DENIED)
                .sourceIp("203.0.113.50").reasonCode("INSUFFICIENT_AUTHORITY").request("GET", "/admin/security").build()));

        String html = mvc.perform(get("/admin/security").with(as("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("보안 대시보드", "분류별 이벤트", "인증·세션", "접근 제어", "LOGIN_FAILURE", "ACCESS_DENIED", "203.0.113.50", "3회 실패");
        assertThat(html).contains("test@test.com");                  // 관리자 화면이라 허용 목록 계정은 이메일 그대로 이름을 붙인다
        assertThat(html).doesNotContain(hash);                       // 계정 식별값(해시) 전체는 화면에도 나오지 않는다
        assertThat(html).contains("href=\"/admin/security\"");        // 관리자에게는 관리 메뉴가 보인다
    }

    @Test
    void unknownRangeAndTypeFallBackInsteadOfFailing() throws Exception {
        mvc.perform(get("/admin/security").param("range", "banana").param("type", "NOT_A_TYPE").param("page", "-4").with(as("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void regularMembersDoNotSeeTheAdminMenu() throws Exception {
        String html = mvc.perform(get("/dashboard").with(as("USER"))).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("/admin/security");
    }

    @Test
    void unlockNeedsAdminAndCsrfAndLeavesAnAuditTrail() throws Exception {
        long vip = members.findByEmail("vip@test.com").orElseThrow().getId();
        AccountLockout lock = new AccountLockout(vip, LocalDateTime.now(ZoneOffset.UTC));
        lock.lock(LocalDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(15));
        lockouts.saveAndFlush(lock);
        String url = "/admin/security/accounts/" + vip + "/unlock";

        mvc.perform(post(url).with(as("USER")).with(csrf())).andExpect(status().isForbidden());        // 일반 회원은 못 푼다
        mvc.perform(post(url).with(as("ADMIN"))).andExpect(status().isForbidden());                    // CSRF 토큰이 없으면 못 푼다
        assertThat(lockouts.findLocked(LocalDateTime.now(ZoneOffset.UTC))).extracting(AccountLockout::getMemberId).contains(vip);

        mvc.perform(post(url).with(as("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/security?range=24h&notice=unlocked"));

        assertThat(lockouts.findLocked(LocalDateTime.now(ZoneOffset.UTC))).extracting(AccountLockout::getMemberId).doesNotContain(vip);
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(SecurityEventType.ACCOUNT_UNLOCKED);
            assertThat(a.getTargetMemberId()).isEqualTo(vip);
            assertThat(a.getReasonCode()).isEqualTo("ADMIN_UNLOCK");
        });
        // 이미 풀린 계정을 다시 풀면 풀었다고 거짓으로 알리지 않는다
        mvc.perform(post(url).with(as("ADMIN")).with(csrf())).andExpect(redirectedUrl("/admin/security?range=24h&notice=notlocked"));
    }
}
