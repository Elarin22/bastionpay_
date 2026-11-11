package com.san.bastionpay.admin;

import com.san.bastionpay.audit.AccountLockout;
import com.san.bastionpay.audit.AccountLockoutRepository;
import com.san.bastionpay.audit.Actor;
import com.san.bastionpay.audit.AuditLog;
import com.san.bastionpay.audit.AuditLogRepository;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventEntity;
import com.san.bastionpay.audit.SecurityEventRepository;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.audit.Severity;
import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRepository;
import jakarta.persistence.EntityManager;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 대시보드의 목록 화면(이벤트·집계·감사 기록·티켓·계정): 분류와 필터, 10건씩 쪽 나누기.
 * 개발 DB 에 실제 이벤트가 있을 수 있어서, 시험 데이터는 문서용 IP 대역(192.0.2.x)으로 가려서 센다. 바꾼 데이터는 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminListsTest {

    private static final String ME = "192.0.2.77";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired SecurityEventRepository events;
    @Autowired AuditLogRepository audits;
    @Autowired AccountLockoutRepository lockouts;
    @Autowired IncidentService incidents;
    @Autowired EntityManager em;

    private RequestPostProcessor admin() {
        Member m = members.findByEmail("admin@test.com").orElseThrow();
        AuthMember auth = new AuthMember(m.getId(), m.getNickname(), "ADMIN", "STANDARD");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(auth, null, auth.authorities()));
    }

    private String page(String url, String... params) throws Exception {
        var req = get(url).with(admin());
        for (int i = 0; i + 1 < params.length; i += 2) req.param(params[i], params[i + 1]);
        return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void event(String ip, SecurityEventType type, Severity severity, EventOutcome outcome, Long memberId) {
        var b = SecurityEvent.of(type, outcome).sourceIp(ip).severity(severity).reasonCode("TEST");
        if (memberId != null) b.memberId(memberId);
        events.save(new SecurityEventEntity(b.build()));
    }

    /** 표의 본문(tbody). 필터 선택 상자의 항목 이름과 섞이지 않게 표 안만 본다. */
    private static String body(String html) {
        int from = html.indexOf("<tbody>");
        return from < 0 ? "" : html.substring(from, html.indexOf("</tbody>"));
    }

    /** 표의 본문 행 수. */
    private static long rows(String html) {
        return body(html).isEmpty() ? 0 : body(html).split("<tr", -1).length - 1;
    }

    // ---- 분류 ----

    @Test
    void everyEventTypeBelongsToExactlyOneCategory() {
        var seen = EnumSet.noneOf(SecurityEventType.class);
        for (SecurityEventType.Category c : SecurityEventType.Category.values()) {
            for (SecurityEventType t : c.types()) {
                assertThat(t.category()).isEqualTo(c);
                assertThat(seen.add(t)).as(t + " 가 두 분류에 들어 있다").isTrue();
            }
        }
        assertThat(seen).containsExactlyInAnyOrder(SecurityEventType.values());
    }

    // ---- 이벤트: 필터와 쪽 나누기 ----

    @Test
    void eventsAreFilteredAndShownTenPerPage() throws Exception {
        long vip = members.findByEmail("vip@test.com").orElseThrow().getId();
        for (int i = 0; i < 23; i++) event(ME, SecurityEventType.LOGIN_FAILURE, Severity.LOW, EventOutcome.FAILURE, null);
        for (int i = 0; i < 4; i++) event(ME, SecurityEventType.ACCESS_DENIED, Severity.MEDIUM, EventOutcome.DENIED, vip);
        event(ME, SecurityEventType.ACCOUNT_LOCKED, Severity.HIGH, EventOutcome.DENIED, vip);
        event("192.0.2.78", SecurityEventType.LOGIN_FAILURE, Severity.LOW, EventOutcome.FAILURE, null);   // 다른 IP

        String p1 = page("/admin/security/events", "ip", ME, "range", "1h");
        assertThat(rows(p1)).isEqualTo(10);
        assertThat(p1).contains("28건");
        assertThat(rows(page("/admin/security/events", "ip", ME, "range", "1h", "page", "3"))).isEqualTo(8);   // 28건 = 10 + 10 + 8
        assertThat(rows(page("/admin/security/events", "ip", ME, "range", "1h", "page", "99"))).as("범위를 벗어나면 마지막 쪽").isEqualTo(8);
        assertThat(p1).contains("page=2").contains("ip=" + ME);   // 쪽 이동 링크가 조건을 유지한다

        // 분류·종류·심각도·결과·회원으로 좁힌다
        assertThat(page("/admin/security/events", "ip", ME, "range", "1h", "category", "ACCESS")).contains("4건");
        assertThat(body(page("/admin/security/events", "ip", ME, "range", "1h", "category", "ACCESS"))).contains("ACCESS_DENIED").doesNotContain("LOGIN_FAILURE");
        assertThat(page("/admin/security/events", "ip", ME, "range", "1h", "type", "ACCOUNT_LOCKED")).contains("1건");
        assertThat(page("/admin/security/events", "ip", ME, "range", "1h", "severity", "HIGH_PLUS")).contains("1건");
        assertThat(page("/admin/security/events", "ip", ME, "range", "1h", "outcome", "FAILURE")).contains("23건");
        assertThat(page("/admin/security/events", "ip", ME, "range", "1h", "member", String.valueOf(vip))).contains("5건");
        // 종류를 고르면 분류보다 종류가 우선한다
        assertThat(page("/admin/security/events", "ip", ME, "range", "1h", "category", "ACCESS", "type", "LOGIN_FAILURE")).contains("23건");
    }

    @Test
    void badIpOrMemberNumberShowsNothingInsteadOfEverything() throws Exception {
        event(ME, SecurityEventType.LOGIN_FAILURE, Severity.LOW, EventOutcome.FAILURE, null);

        String html = page("/admin/security/events", "ip", "evil.example.com");
        assertThat(html).contains("형식이 올바르지 않습니다").contains("0건");
        assertThat(page("/admin/security/events", "member", "abc")).contains("형식이 올바르지 않습니다");
        // 알 수 없는 분류·종류·심각도는 "전체"로 본다(오류 화면이 나오지 않는다)
        page("/admin/security/events", "category", "NOPE", "type", "NOPE", "severity", "NOPE", "outcome", "NOPE", "page", "-5");
    }

    // ---- 집계 ----

    @Test
    void statsGroupByIpWithMinimumCountAndTenPerPage() throws Exception {
        // 12개 IP: 192.0.2.101 은 3번, 나머지 11개는 1번
        for (int i = 0; i < 3; i++) event("192.0.2.101", SecurityEventType.LOGIN_FAILURE, Severity.LOW, EventOutcome.FAILURE, null);
        for (int i = 102; i <= 112; i++) event("192.0.2." + i, SecurityEventType.LOGIN_FAILURE, Severity.LOW, EventOutcome.FAILURE, null);

        String first = page("/admin/security/stats", "range", "1h", "by", "ip", "type", "LOGIN_FAILURE");
        assertThat(rows(first)).isEqualTo(10);
        assertThat(first.indexOf("192.0.2.101")).as("많은 순").isLessThan(first.indexOf("192.0.2.102"));
        assertThat(first).contains("3회", "page=2").contains("by=ip");

        String min = page("/admin/security/stats", "range", "1h", "by", "ip", "type", "LOGIN_FAILURE", "min", "3");
        assertThat(min).contains("192.0.2.101").doesNotContain("192.0.2.102");
        // IP 행에서 바로 티켓·차단으로 대응한다
        assertThat(min).contains("action=\"/admin/security/tickets\"", "action=\"/admin/security/blocked-ips\"");
    }

    @Test
    void statsGroupByAccountAndMember() throws Exception {
        long vip = members.findByEmail("vip@test.com").orElseThrow().getId();
        var hasher = new com.san.bastionpay.audit.LoginSubjectHasher(new com.san.bastionpay.audit.SecurityProperties(java.util.List.of(), "x", true));   // 키가 달라 값이 일치하지 않는다
        events.save(new SecurityEventEntity(SecurityEvent.of(SecurityEventType.LOGIN_FAILURE, EventOutcome.FAILURE).sourceIp(ME)
                .loginSubjectHash(hasher.hash("nobody@example.test")).build()));
        for (int i = 0; i < 2; i++) event(ME, SecurityEventType.ACCESS_DENIED, Severity.MEDIUM, EventOutcome.DENIED, vip);

        assertThat(page("/admin/security/stats", "range", "1h", "by", "account", "type", "LOGIN_FAILURE")).contains("식별값");
        String byMember = page("/admin/security/stats", "range", "1h", "by", "member", "type", "ACCESS_DENIED");
        assertThat(byMember).contains("vip_test", "vip@test.com", "2회");
        // 알 수 없는 기준·종류는 기본값(IP별·로그인 실패)으로 본다
        page("/admin/security/stats", "by", "nope", "type", "NOPE", "min", "-3");
    }

    // ---- 감사 기록 ----

    @Test
    void auditRecordsAreFilteredByActorActionAndTargetTenPerPage() throws Exception {
        long vip = members.findByEmail("vip@test.com").orElseThrow().getId();
        long vvip = members.findByEmail("vvip@test.com").orElseThrow().getId();
        Actor api = Actor.api();
        for (int i = 0; i < 12; i++) {
            var e = SecurityEvent.of(SecurityEventType.IP_BLOCKED, EventOutcome.SUCCESS).sourceIp(ME).reasonCode("API_BLOCK").build();
            audits.save(new AuditLog(e, api.type(), null, null));
        }
        var lock = SecurityEvent.of(SecurityEventType.ACCOUNT_LOCKED, EventOutcome.DENIED).sourceIp(ME).reasonCode("ADMIN_LOCK").build();
        audits.save(new AuditLog(lock, AuditLog.ActorType.ADMIN, members.findByEmail("admin@test.com").orElseThrow().getId(), vip));
        audits.save(new AuditLog(lock, AuditLog.ActorType.SYSTEM, null, vvip));

        String apiOnly = page("/admin/security/audit", "range", "1h", "actor", "API");
        assertThat(rows(apiOnly)).isEqualTo(10);
        assertThat(apiOnly).contains("12건", "page=2").contains("actor=API");
        assertThat(rows(page("/admin/security/audit", "range", "1h", "actor", "API", "page", "2"))).isEqualTo(2);

        String byAdmin = page("/admin/security/audit", "range", "1h", "actor", "ADMIN", "action", "ACCOUNT_LOCKED");
        assertThat(rows(byAdmin)).isEqualTo(1);
        assertThat(body(byAdmin)).contains("관리자 admin_test", "ACCOUNT_LOCKED", "#" + vip).doesNotContain("IP_BLOCKED");
        assertThat(page("/admin/security/audit", "range", "1h", "target", String.valueOf(vvip))).contains("시스템", "#" + vvip);
        assertThat(page("/admin/security/audit", "target", "abc")).contains("숫자로 입력해 주세요");
    }

    // ---- 티켓·계정 목록 ----

    @Test
    void ticketsAreFilteredByIpAndShownTenPerPage() throws Exception {
        Actor api = Actor.api();
        for (int i = 1; i <= 12; i++) incidents.create("192.0.2." + (120 + i), null, Severity.LOW, 1, api);
        incidents.create("198.51.100.5", null, Severity.HIGH, 1, api);
        em.flush();

        String p1 = page("/admin/security/tickets", "ip", "192.0.2.");
        assertThat(p1).contains("page=2").contains("ip=192.0.2.");
        assertThat(rows(p1)).isEqualTo(10);
        assertThat(rows(page("/admin/security/tickets", "ip", "192.0.2.", "page", "2"))).isEqualTo(2);
        assertThat(page("/admin/security/tickets", "ip", "198.51.100", "severity", "HIGH")).contains("198.51.100.5").doesNotContain("192.0.2.12");
        assertThat(page("/admin/security/tickets", "ip", "198.51.100", "severity", "LOW")).contains("조건에 맞는 티켓이 없습니다");
    }

    @Test
    void accountListFiltersByTierAndLockKind() throws Exception {
        long vvip = members.findByEmail("vvip@test.com").orElseThrow().getId();
        // vvip 는 로그인 실패로 자동 잠금, vip 는 관리자가 잠금
        AccountLockout auto = new AccountLockout(vvip, LocalDateTime.now(ZoneOffset.UTC));
        auto.lock(LocalDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(15));
        lockouts.saveAndFlush(auto);
        Member vip = members.findByEmail("vip@test.com").orElseThrow();
        vip.lock("테스트", LocalDateTime.now(ZoneOffset.UTC));
        members.saveAndFlush(vip);

        assertThat(body(page("/admin/security/accounts", "tier", "VIP"))).contains("vip_test").doesNotContain("vvip_test");
        String auto1 = page("/admin/security/accounts", "status", "AUTO");
        assertThat(body(auto1)).contains("vvip_test", "잠김(로그인 실패)").doesNotContain("<strong>vip_test<");
        String manual = page("/admin/security/accounts", "status", "LOCKED");
        assertThat(body(manual)).contains("vip_test", "잠김(관리자)").doesNotContain("vvip_test");
        String any = page("/admin/security/accounts", "status", "ANY_LOCKED");
        assertThat(any).contains("vip_test", "vvip_test");
        assertThat(page("/admin/security/accounts", "status", "LOCKED", "tier", "VVIP")).contains("조건에 맞는 회원이 없습니다");   // 관리자가 잠근 VVIP 는 없다
        assertThat(page("/admin/security/accounts", "status", "NOPE", "tier", "NOPE", "role", "NOPE")).contains("회원");   // 알 수 없는 값은 전체
    }

    // ---- 개요 ----

    @Test
    void overviewGroupsEventsByCategoryAndLinksToTheFilteredLists() throws Exception {
        event(ME, SecurityEventType.LOGIN_FAILURE, Severity.LOW, EventOutcome.FAILURE, null);
        event(ME, SecurityEventType.IP_BLOCKED, Severity.HIGH, EventOutcome.SUCCESS, null);

        String html = page("/admin/security", "range", "1h");

        assertThat(html).contains("분류별 이벤트", "인증·세션", "보안 대응", "높음 이상 1");
        assertThat(html).contains("/admin/security/events?range=1h&amp;type=LOGIN_FAILURE", "/admin/security/events?range=1h&amp;category=AUTH");
        assertThat(html).contains("최근 높음 이상 이벤트", "IP_BLOCKED");
        // 탭 7개가 모두 있다
        for (String tab : new String[]{"/admin/security\"", "/admin/security/events", "/admin/security/stats", "/admin/security/tickets", "/admin/security/blocked-ips", "/admin/security/accounts", "/admin/security/audit"}) {
            assertThat(html).contains("href=\"" + tab);
        }
        assertThat(Arrays.stream(SecurityEventType.Category.values()).map(SecurityEventType.Category::label).collect(Collectors.toList())).allSatisfy(label -> assertThat(html).contains(label));
    }
}
