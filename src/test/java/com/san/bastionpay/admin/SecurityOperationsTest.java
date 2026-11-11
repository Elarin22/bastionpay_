package com.san.bastionpay.admin;

import com.san.bastionpay.audit.Actor;
import com.san.bastionpay.audit.AuditLog;
import com.san.bastionpay.audit.AuditLogRepository;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.IpAddresses;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventEntity;
import com.san.bastionpay.audit.SecurityEventRepository;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.audit.Severity;
import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRepository;
import com.san.bastionpay.member.MemberRole;
import com.san.bastionpay.member.MemberStatus;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 대응 기능: 인시던트 티켓, IP 차단, 계정 잠금·해제, 권한 회수, 그리고 서버 간 API(X-API-Key).
 * 바꾼 DB 는 롤백된다. 로컬 MySQL 과 db/seed/member.sql 의 더미 회원이 필요하다.
 */
@SpringBootTest(properties = "app.security.admin.api-key=test-api-key-for-this-test-only")
@AutoConfigureMockMvc
@Transactional
class SecurityOperationsTest {

    private static final String KEY = "test-api-key-for-this-test-only";
    private static final String ATTACKER = "203.0.113.50";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired SecurityEventRepository events;
    @Autowired AuditLogRepository audits;
    @Autowired BlockedIpService blocked;
    @Autowired IncidentService incidents;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    // ---- 도우미 ----

    private Member member(String email) {
        return members.findByEmail(email).orElseThrow();
    }

    private RequestPostProcessor as(String email) {
        Member m = member(email);
        AuthMember auth = new AuthMember(m.getId(), m.getNickname(), m.getRole().name(), m.getTier().name());
        return authentication(UsernamePasswordAuthenticationToken.authenticated(auth, null, auth.authorities()));
    }

    private ResultActions adminPost(String url, String... params) throws Exception {
        var req = post(url).with(as("admin@test.com")).with(csrf());
        for (int i = 0; i + 1 < params.length; i += 2) req.param(params[i], params[i + 1]);
        return mvc.perform(req);
    }

    private ResultActions api(String method, String url, String body) throws Exception {
        var req = method.equals("GET") ? get(url) : post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req.header("X-API-Key", KEY));
    }

    private void promoteToAdmin(String email) {
        em.flush();
        jdbc.update("update member set role = 'ADMIN' where email = ?", email);
        em.clear();
    }

    /** 테스트 데이터(db/seed/test-data.sql)가 들어 있어도 관리자가 admin@test.com 하나뿐인 상태로 시작한다(롤백된다). */
    private void keepOnlyAdmin() {
        em.flush();
        jdbc.update("update member set role = 'USER' where role = 'ADMIN' and email <> 'admin@test.com'");
        em.clear();
    }

    /** 이미 열려 있는 티켓(시험 데이터 등)을 모두 닫고 시작한다(롤백된다). */
    private void closeExistingTickets() {
        incidents.closeAllOpen(null, Actor.system());
        em.flush();
    }

    private void securityEvent(String ip, SecurityEventType type, Severity severity) {
        events.save(new SecurityEventEntity(SecurityEvent.of(type, EventOutcome.DENIED).sourceIp(ip).severity(severity).reasonCode("TEST").build()));
        em.flush();
    }

    // ---- 접근 제어 ----

    @Test
    void onlyAdminsCanOpenTheSecurityManagementPages() throws Exception {
        for (String url : new String[]{"/admin/security/tickets", "/admin/security/blocked-ips", "/admin/security/accounts"}) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(as("test@test.com"))).andExpect(status().isForbidden());
            mvc.perform(get(url).with(as("admin@test.com"))).andExpect(status().isOk());
        }
        // 일반 회원은 조치도 못 한다
        mvc.perform(post("/admin/security/blocked-ips").with(as("test@test.com")).with(csrf()).param("ip", ATTACKER)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/security/accounts/action").with(as("test@test.com")).with(csrf()).param("action", "lock").param("target", "1")).andExpect(status().isForbidden());
        // CSRF 토큰 없이는 관리자도 못 한다
        mvc.perform(post("/admin/security/tickets").with(as("admin@test.com")).param("ip", ATTACKER)).andExpect(status().isForbidden());
    }

    // ---- 인시던트 티켓 ----

    @Test
    void ticketCollectsEventsOfThatIpAndOnlyOneOpenTicketExistsPerIp() throws Exception {
        securityEvent(ATTACKER, SecurityEventType.LOGIN_FAILURE, Severity.LOW);
        securityEvent(ATTACKER, SecurityEventType.ACCESS_DENIED, Severity.HIGH);
        securityEvent("198.51.100.9", SecurityEventType.LOGIN_FAILURE, Severity.CRITICAL);   // 다른 IP 는 섞이지 않는다

        adminPost("/admin/security/tickets", "ip", ATTACKER).andExpect(redirectedUrl("/admin/security/tickets?notice=created"));
        adminPost("/admin/security/tickets", "ip", ATTACKER, "severity", "LOW").andExpect(redirectedUrl("/admin/security/tickets?notice=updated"));

        var tickets = jdbcTickets(ATTACKER);
        assertThat(tickets).hasSize(1);
        assertThat(tickets.getFirst()).containsEntry("severity", "HIGH")                // 낮은 심각도로 갱신해도 내려가지 않는다
                .containsEntry("status", "OPEN").containsEntry("event_count", 2);
        assertThat(String.valueOf(tickets.getFirst().get("summary"))).contains(ATTACKER, "LOGIN_FAILURE", "ACCESS_DENIED").doesNotContain("198.51.100.9");
        assertThat(audits.findAll()).anySatisfy(a -> assertThat(a.getAction()).isEqualTo(SecurityEventType.INCIDENT_CREATED));
        adminPost("/admin/security/tickets", "ip", "not an ip").andExpect(redirectedUrl("/admin/security/tickets?notice=bad_ip"));
    }

    @Test
    void closedTicketsAreNotReopenedAndTheNextTicketOnlyCollectsNewEvents() throws Exception {
        securityEvent(ATTACKER, SecurityEventType.LOGIN_FAILURE, Severity.MEDIUM);
        adminPost("/admin/security/tickets", "ip", ATTACKER);
        long id = ((Number) jdbcTickets(ATTACKER).getFirst().get("id")).longValue();

        adminPost("/admin/security/tickets/close", "ids", String.valueOf(id)).andExpect(redirectedUrl("/admin/security/tickets?closedCount=1&notice=closed"));
        adminPost("/admin/security/tickets/close", "ids", String.valueOf(id)).andExpect(redirectedUrl("/admin/security/tickets?notice=none"));   // 이미 종료: 시각을 덮어쓰지 않는다

        // 종료 시각 이전의 사건은 새 티켓이 다시 흡수하지 않는다
        adminPost("/admin/security/tickets", "ip", ATTACKER).andExpect(redirectedUrl("/admin/security/tickets?notice=created"));
        assertThat(jdbcTickets(ATTACKER)).hasSize(2).anySatisfy(t -> assertThat(t).containsEntry("status", "OPEN").containsEntry("event_count", 0));
    }

    @Test
    void closeAllClosesEveryOpenTicketOrOnlyTheChosenSeverity() throws Exception {
        closeExistingTickets();
        securityEvent("203.0.113.1", SecurityEventType.LOGIN_FAILURE, Severity.HIGH);
        securityEvent("203.0.113.2", SecurityEventType.LOGIN_FAILURE, Severity.LOW);
        securityEvent("203.0.113.3", SecurityEventType.LOGIN_FAILURE, Severity.LOW);
        for (String ip : new String[]{"203.0.113.1", "203.0.113.2", "203.0.113.3"}) adminPost("/admin/security/tickets", "ip", ip);
        long openBefore = openTickets();

        // 심각도를 고르면 그 심각도의 열린 티켓만 종료한다
        adminPost("/admin/security/tickets/close-all", "severity", "HIGH").andExpect(redirectedUrl("/admin/security/tickets?closedCount=1&notice=closed"));
        assertThat(openTickets()).isEqualTo(openBefore - 1);
        assertThat(jdbcTickets("203.0.113.1").getFirst()).containsEntry("status", "CLOSED");
        assertThat(jdbcTickets("203.0.113.2").getFirst()).containsEntry("status", "OPEN");

        adminPost("/admin/security/tickets/close-all").andExpect(status().is3xxRedirection());
        assertThat(openTickets()).isZero();
        adminPost("/admin/security/tickets/close-all").andExpect(redirectedUrl("/admin/security/tickets?notice=none"));   // 더 종료할 것이 없다
        // 일반 회원은 못 한다
        mvc.perform(post("/admin/security/tickets/close-all").with(as("test@test.com")).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void closeAllButtonAsksForConfirmationBeforeAnythingIsClosed() throws Exception {
        securityEvent("203.0.113.1", SecurityEventType.LOGIN_FAILURE, Severity.HIGH);
        adminPost("/admin/security/tickets", "ip", "203.0.113.1");

        String html = mvc.perform(get("/admin/security/tickets").with(as("admin@test.com"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("열린 티켓 전체 종료", "모두 종료합니다", "action=\"/admin/security/tickets/close-all\"");
        assertThat(openTickets()).isGreaterThan(0);   // 화면을 열기만 해서는 아무것도 종료되지 않는다
    }

    @Test
    void apiCanCloseAllOpenTickets() throws Exception {
        closeExistingTickets();
        securityEvent("203.0.113.1", SecurityEventType.LOGIN_FAILURE, Severity.HIGH);
        securityEvent("203.0.113.2", SecurityEventType.LOGIN_FAILURE, Severity.LOW);
        api("POST", "/api/admin/incident", "{\"src_ip\":\"203.0.113.1\"}");
        api("POST", "/api/admin/incident", "{\"src_ip\":\"203.0.113.2\"}");

        api("POST", "/api/admin/incidents/close", "{\"all_open\":true,\"severity\":\"nope\"}").andExpect(status().isBadRequest());
        api("POST", "/api/admin/incidents/close", "{\"all_open\":true,\"severity\":\"high\"}").andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        api("POST", "/api/admin/incidents/close", "{\"all_open\":true}").andExpect(status().isOk());
        assertThat(openTickets()).isZero();
        api("POST", "/api/admin/incidents/close", "{}").andExpect(status().isBadRequest());
    }

    private long openTickets() {
        em.flush();
        return jdbc.queryForObject("select count(*) from incident where status = 'OPEN'", Long.class);
    }

    private java.util.List<java.util.Map<String, Object>> jdbcTickets(String ip) {
        em.flush();
        return jdbc.queryForList("select id, severity, status, event_count, summary from incident where src_ip = ? order by id", ip);
    }

    // ---- IP 차단 ----

    @Test
    void blockedIpIsRefusedBeforeReachingTheAppAndUnblockRestoresIt() throws Exception {
        RequestPostProcessor from = r -> { r.setRemoteAddr(ATTACKER); return r; };
        mvc.perform(get("/login").with(from)).andExpect(status().isOk());

        adminPost("/admin/security/blocked-ips", "ip", ATTACKER, "reason", "테스트").andExpect(redirectedUrl("/admin/security/blocked-ips?notice=blocked"));

        mvc.perform(get("/login").with(from)).andExpect(status().isForbidden());
        mvc.perform(post("/login").with(from).with(csrf()).param("email", "test@test.com").param("password", "12345678A!")).andExpect(status().isForbidden());
        // 유효한 API 키를 가진 자동화는 같은 IP 에서 와도 막히지 않는다(자동화가 자기 차단으로 멈추지 않게)
        mvc.perform(get("/api/admin/blocked").with(from).header("X-API-Key", KEY)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/blocked").with(from).header("X-API-Key", "wrong")).andExpect(status().isForbidden());   // 키가 틀리면 차단이 먼저

        adminPost("/admin/security/blocked-ips/unblock", "ip", ATTACKER).andExpect(redirectedUrl("/admin/security/blocked-ips?notice=unblocked"));
        mvc.perform(get("/login").with(from)).andExpect(status().isOk());
        assertThat(audits.findAll()).extracting(AuditLog::getAction).contains(SecurityEventType.IP_BLOCKED, SecurityEventType.IP_UNBLOCKED);
    }

    @Test
    void blockingGuardsRejectOwnIpBadInputAndDuplicates() throws Exception {
        // MockMvc 요청의 출발지는 127.0.0.1 이고 관리자가 바로 그 IP 로 접속 중이다
        adminPost("/admin/security/blocked-ips", "ip", "127.0.0.1").andExpect(redirectedUrl("/admin/security/blocked-ips?notice=self"));
        adminPost("/admin/security/blocked-ips", "ip", "evil.example.com").andExpect(redirectedUrl("/admin/security/blocked-ips?notice=invalid"));
        adminPost("/admin/security/blocked-ips", "ip", ATTACKER, "reason", "처음 사유");
        adminPost("/admin/security/blocked-ips", "ip", ATTACKER, "reason", "다른 사유").andExpect(redirectedUrl("/admin/security/blocked-ips?notice=already"));
        assertThat(jdbc.queryForObject("select reason from blocked_ip where ip = ?", String.class, ATTACKER)).isEqualTo("처음 사유");   // 덮어쓰지 않는다
        adminPost("/admin/security/blocked-ips/unblock", "ip", "198.51.100.1").andExpect(redirectedUrl("/admin/security/blocked-ips?notice=notfound"));
        assertThat(blocked.isBlocked("127.0.0.1")).isFalse();
    }

    @Test
    void ipSpellingsAreNormalizedSoTheSameAddressMatches() {
        assertThat(IpAddresses.normalize("2001:db8::1")).isEqualTo(IpAddresses.normalize("2001:0db8:0:0:0:0:0:1"));
        assertThat(IpAddresses.normalize(" 203.0.113.9 ")).contains("203.0.113.9");
        assertThat(IpAddresses.normalize("2001:0db8:0:0:0:0:0:0007")).contains("2001:db8::7");   // 짧은 표기로 통일
        assertThat(IpAddresses.normalize("0:0:0:0:0:0:0:1")).contains("::1");
        assertThat(IpAddresses.normalize("2001:db8:0:1:0:0:0:1")).contains("2001:db8:0:1::1");   // 가장 긴 0 구간만 줄인다
        for (String bad : new String[]{null, "", "evil.example.com", "999.1.1.1", "1.2.3", "localhost", "1.2.3.4; drop"}) {
            assertThat(IpAddresses.normalize(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    // ---- 계정 잠금·해제 ----

    @Test
    void lockingCutsTheAccountOffImmediatelyAndOnlyTestAccountsCanBeLocked() throws Exception {
        Cookie access = loginAs("vip@test.com");
        mvc.perform(get("/session/status").cookie(access)).andExpect(status().isOk());

        adminPost("/admin/security/accounts/action", "action", "lock", "target", "vip@test.com", "reason", "의심스러운 활동")
                .andExpect(redirectedUrl("/admin/security/accounts?notice=locked"));

        mvc.perform(get("/session/status").cookie(access)).andExpect(status().isUnauthorized());   // 이미 받은 토큰도 바로 못 쓴다
        Member vip = member("vip@test.com");
        assertThat(vip.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(vip.getLockReason()).isEqualTo("의심스러운 활동");
        mvc.perform(post("/login").with(csrf()).param("email", "vip@test.com").param("password", "12345678A!")).andExpect(status().isUnauthorized());   // 비밀번호가 맞아도 로그인 불가
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(SecurityEventType.ACCOUNT_LOCKED);
            assertThat(a.getActorType()).isEqualTo(AuditLog.ActorType.ADMIN);
            assertThat(a.getReasonCode()).isEqualTo("ADMIN_LOCK");
        });

        adminPost("/admin/security/accounts/action", "action", "lock", "target", "vip@test.com").andExpect(redirectedUrl("/admin/security/accounts?notice=already_locked"));
        adminPost("/admin/security/accounts/action", "action", "lock", "target", "admin@test.com").andExpect(redirectedUrl("/admin/security/accounts?notice=self"));
        promoteToAdmin("vvip@test.com");
        adminPost("/admin/security/accounts/action", "action", "lock", "target", "vvip@test.com").andExpect(redirectedUrl("/admin/security/accounts?notice=not_allowed"));   // 관리자는 잠글 수 없다
        adminPost("/admin/security/accounts/action", "action", "lock", "target", "nobody@example.test").andExpect(redirectedUrl("/admin/security/accounts?notice=not_found"));
    }

    @Test
    void unlockRestoresLoginAndAnUnlockedAccountSaysSo() throws Exception {
        adminPost("/admin/security/accounts/action", "action", "lock", "target", String.valueOf(member("vip@test.com").getId()));
        adminPost("/admin/security/accounts/action", "action", "unlock", "target", "vip@test.com").andExpect(redirectedUrl("/admin/security/accounts?notice=unlocked"));

        assertThat(member("vip@test.com").getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member("vip@test.com").getLockReason()).isNull();
        mvc.perform(post("/login").with(csrf()).param("email", "vip@test.com").param("password", "12345678A!")).andExpect(status().is3xxRedirection());
        adminPost("/admin/security/accounts/action", "action", "unlock", "target", "vip@test.com").andExpect(redirectedUrl("/admin/security/accounts?notice=not_locked"));
    }

    private Cookie loginAs(String email) throws Exception {
        return mvc.perform(post("/login").with(csrf()).param("email", email).param("password", "12345678A!")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getCookie("access_token");
    }

    // ---- 권한 회수 ----

    @Test
    void revokeTakesAdminRightsAwayAndCutsTheirSessionButNeverFromTheLastAdminOrYourself() throws Exception {
        keepOnlyAdmin();
        promoteToAdmin("vip@test.com");
        Cookie vipToken = loginAs("vip@test.com");
        mvc.perform(get("/admin/security").cookie(vipToken)).andExpect(status().isOk());

        adminPost("/admin/security/accounts/action", "action", "revoke", "target", "vip@test.com", "reason", "허용 목록 밖").andExpect(redirectedUrl("/admin/security/accounts?notice=revoked"));

        assertThat(member("vip@test.com").getRole()).isEqualTo(MemberRole.USER);
        mvc.perform(get("/session/status").cookie(vipToken)).andExpect(status().isUnauthorized());   // 이미 받은 관리자 토큰도 못 쓴다
        adminPost("/admin/security/accounts/action", "action", "revoke", "target", "vip@test.com").andExpect(redirectedUrl("/admin/security/accounts?notice=not_admin"));
        adminPost("/admin/security/accounts/action", "action", "revoke", "target", "admin@test.com").andExpect(redirectedUrl("/admin/security/accounts?notice=self"));
        assertThat(audits.findAll()).anySatisfy(a -> assertThat(a.getAction()).isEqualTo(SecurityEventType.ROLE_REVOKED));
        // 관리자가 한 명만 남았으면(자동화 호출처럼 자기 자신이 아니어도) 회수하지 않는다
        api("POST", "/api/admin/revoke", "{\"email\":\"admin@test.com\"}").andExpect(status().isConflict());
        assertThat(member("admin@test.com").getRole()).isEqualTo(MemberRole.ADMIN);
    }

    @Test
    void adminsOutsideTheAllowlistAreShownAsOverPrivileged() throws Exception {
        keepOnlyAdmin();
        promoteToAdmin("vip@test.com");

        String html = mvc.perform(get("/admin/security/accounts").with(as("admin@test.com"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("허용 목록 밖 관리자", "vip_test", "vip@test.com").doesNotContain("vi***@test.com");   // 관리자 화면에서는 이메일을 그대로 보여 준다
        api("GET", "/api/admin/violations", null).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.violations[0].nickname").value("vip_test"));
    }

    @Test
    void memberSearchTreatsPercentAndUnderscoreAsPlainCharacters() throws Exception {
        String html = mvc.perform(get("/admin/security/accounts").param("q", "%").with(as("admin@test.com"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("조건에 맞는 회원이 없습니다");
    }

    // ---- 서버 간 API ----

    @Test
    void apiRefusesRequestsWithoutAValidKeyAndLogsTheAttempt() throws Exception {
        mvc.perform(get("/api/admin/incidents")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.msg").exists());
        mvc.perform(get("/api/admin/incidents").header("X-API-Key", "wrong-key")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/lock").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"vip@test.com\"}")).andExpect(status().isUnauthorized());
        // 일반 로그인(쿠키)으로는 API 를 쓸 수 없다 — 사람은 화면을 쓴다
        mvc.perform(get("/api/admin/incidents").with(as("admin@test.com"))).andExpect(status().isUnauthorized());
        assertThat(member("vip@test.com").getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void apiCreatesTicketsAndAppliesTheSameRulesAsTheScreen() throws Exception {
        securityEvent(ATTACKER, SecurityEventType.LOGIN_FAILURE, Severity.HIGH);

        api("POST", "/api/admin/incident", "{\"src_ip\":\"" + ATTACKER + "\",\"title\":\"무차별 대입\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true)).andExpect(jsonPath("$.incident.severity").value("HIGH"))
                .andExpect(jsonPath("$.incident.created_by").value("api")).andExpect(jsonPath("$.incident.event_count").value(1));
        api("POST", "/api/admin/incident", "{\"src_ip\":\"" + ATTACKER + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        api("POST", "/api/admin/incident", "{\"src_ip\":\"nope\"}").andExpect(status().isBadRequest());
        api("POST", "/api/admin/incident", "{\"src_ip\":\"" + ATTACKER + "\",\"severity\":\"INFO\"}").andExpect(status().isBadRequest());
        api("GET", "/api/admin/incidents?status=open", null).andExpect(status().isOk()).andExpect(jsonPath("$.incidents[0].src_ip").value(ATTACKER));
        long id = ((Number) jdbcTickets(ATTACKER).getFirst().get("id")).longValue();
        api("POST", "/api/admin/incident/close", "{\"id\":" + id + "}").andExpect(status().isOk());
        api("POST", "/api/admin/incident/close", "{\"id\":" + id + "}").andExpect(status().isNotFound());

        api("POST", "/api/admin/block", "{\"ip\":\"" + ATTACKER + "\",\"reason\":\"api\"}").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        api("POST", "/api/admin/block", "{\"ip\":\"" + ATTACKER + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false));
        api("POST", "/api/admin/block", "{\"ip\":\"x\"}").andExpect(status().isBadRequest());
        api("GET", "/api/admin/blocked", null).andExpect(status().isOk()).andExpect(jsonPath("$.blocked[0].blocked_by").value("api"));
        api("POST", "/api/admin/unblock", "{\"ip\":\"" + ATTACKER + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));

        api("POST", "/api/admin/lock", "{\"email\":\"vip@test.com\",\"reason\":\"자동 대응\"}").andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true));
        assertThat(member("vip@test.com").getStatus()).isEqualTo(MemberStatus.LOCKED);
        api("POST", "/api/admin/lock", "{\"email\":\"admin@test.com\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.locked").value(false));   // 잠갔다고 거짓 응답하지 않는다
        api("POST", "/api/admin/lock", "{\"email\":\"nobody@example.test\"}").andExpect(status().isNotFound());
        api("POST", "/api/admin/unlock", "{\"member_id\":" + member("vip@test.com").getId() + "}").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        api("GET", "/api/admin/users?role=admin", null).andExpect(status().isOk()).andExpect(jsonPath("$.users[0].email_masked").value("ad***@test.com"));
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getActorType()).isEqualTo(AuditLog.ActorType.API);
            assertThat(a.getAction()).isEqualTo(SecurityEventType.ACCOUNT_LOCKED);
            assertThat(a.getReasonCode()).isEqualTo("API_LOCK");
        });
    }

    @Test
    void apiIsClosedWhenNoKeyIsConfigured() {
        var props = new AdminSecurityProperties("", java.util.List.of());
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-API-Key", "");
        assertThat(new AdminApiAuth(props, null).matches(request)).isFalse();
        assertThat(props.toString()).doesNotContain("secret").contains("(unset)");
        assertThat(new AdminSecurityProperties("k", null).toString()).doesNotContain("k]").contains("***");
    }
}
