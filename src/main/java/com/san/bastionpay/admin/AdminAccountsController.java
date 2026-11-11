package com.san.bastionpay.admin;

import com.san.bastionpay.app.Pager;
import com.san.bastionpay.audit.AccountLockoutRepository;
import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRole;
import com.san.bastionpay.member.MemberStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 화면: 회원 계정 잠금·해제와 관리자 권한 회수, 허용 목록 밖 관리자(과잉권한) 표시.
 * 관리자 화면이라 이메일을 그대로 보여 준다(서버 간 API 응답은 가린다). 조치 한 번마다 보안 이벤트와 감사 기록(누가 했는지)이 남는다.
 */
@Controller
@RequestMapping("/admin/security/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountsController {

    static final int PAGE_SIZE = 10;
    private static final Set<String> STATES = Set.of("all", "ACTIVE", "LOCKED", "AUTO", "ANY_LOCKED");
    private static final Map<String, String> NOTICES = Map.ofEntries(
            Map.entry("locked", "계정을 잠갔습니다. 그 계정의 로그인 세션도 모두 끊었습니다."),
            Map.entry("already_locked", "이미 잠긴 계정입니다."),
            Map.entry("not_allowed", "잠글 수 없는 계정입니다. 자동 잠금 대상(합성 테스트 계정)만 잠글 수 있고, 관리자 계정은 잠글 수 없습니다."),
            Map.entry("not_active", "활성 상태가 아니라 잠글 수 없습니다."),
            Map.entry("self", "자기 자신에게는 이 조치를 할 수 없습니다."),
            Map.entry("unlocked", "잠금을 풀었습니다. 감사 기록에 남았습니다."),
            Map.entry("not_locked", "잠겨 있지 않은 계정입니다."),
            Map.entry("revoked", "관리자 권한을 회수했습니다. 그 계정의 로그인 세션도 모두 끊었습니다."),
            Map.entry("not_admin", "관리자 권한이 없는 계정입니다."),
            Map.entry("last_admin", "마지막 관리자의 권한은 회수할 수 없습니다. 관리자가 한 명도 없게 됩니다."),
            Map.entry("not_found", "회원을 찾을 수 없습니다."),
            Map.entry("bad_action", "알 수 없는 조치입니다."));

    private final MemberAdminService service;
    private final AccountLockoutRepository lockouts;

    public AdminAccountsController(MemberAdminService service, AccountLockoutRepository lockouts) {
        this.service = service;
        this.lockouts = lockouts;
    }

    public record Row(long id, String nickname, String email, String role, String tier, String state, boolean lockable, boolean canUnlock,
                      boolean canRevoke, boolean overPrivileged, boolean self) {}

    @GetMapping
    public String page(@AuthenticationPrincipal AuthMember admin, @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "all") String role, @RequestParam(defaultValue = "all") String status,
                       @RequestParam(defaultValue = "all") String tier, @RequestParam(defaultValue = "1") int page,
                       @RequestParam(required = false) String notice, Model model) {
        MemberRole r = parse(MemberRole.class, role);
        String st = STATES.contains(status) ? status : "all";
        var t = parse(com.san.bastionpay.member.MemberTier.class, tier);
        PagedList<Member> result = service.list(q, r, st, t, page);
        Set<Long> autoLocked = lockouts.findLocked(LocalDateTime.now(ZoneOffset.UTC)).stream().map(l -> l.getMemberId()).collect(Collectors.toSet());
        model.addAttribute("rows", result.rows().stream().map(m -> row(m, admin, autoLocked)).toList());
        model.addAttribute("violations", service.violations().stream().map(m -> row(m, admin, autoLocked)).toList());
        model.addAttribute("total", result.total());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("role", r == null ? "all" : r.name());
        model.addAttribute("status", st);
        model.addAttribute("tier", t == null ? "all" : t.name());
        model.addAttribute("tiers", com.san.bastionpay.member.MemberTier.values());
        model.addAttribute("pager", Pager.of(result.page(), result.totalPages(), p -> href(q, role, st, t == null ? "all" : t.name(), p)));
        model.addAttribute("notice", notice == null ? null : NOTICES.get(notice));
        model.addAttribute("activeNav", "admin");
        model.addAttribute("tab", "accounts");
        return "admin/accounts";
    }

    /** 조치 한 곳으로 모은 입구: action = lock | unlock | revoke. target 은 회원 id 또는 이메일. */
    @PostMapping("/action")
    public String act(@AuthenticationPrincipal AuthMember admin, @RequestParam String action, @RequestParam String target,
                      @RequestParam(required = false) String reason) {
        Long id = null;
        String email = null;
        String t = target == null ? "" : target.strip();
        if (t.matches("\\d{1,18}")) id = Long.parseLong(t);
        else email = t;
        Member member = service.find(id, email).orElse(null);
        if (member == null) return AdminActions.redirect("/admin/security/accounts", "not_found");
        var actor = AdminActions.actor(admin);
        String notice = switch (action) {
            case "lock" -> switch (service.lock(member.getId(), reason, actor)) {
                case LOCKED -> "locked";
                case ALREADY_LOCKED -> "already_locked";
                case NOT_FOUND -> "not_found";
                case SELF -> "self";
                case NOT_ALLOWED -> "not_allowed";
                case NOT_ACTIVE -> "not_active";
            };
            case "unlock" -> switch (service.unlock(member.getId(), actor)) {
                case UNLOCKED -> "unlocked";
                case NOT_LOCKED -> "not_locked";
                case NOT_FOUND -> "not_found";
            };
            case "revoke" -> switch (service.revokeAdmin(member.getId(), reason, actor)) {
                case REVOKED -> "revoked";
                case NOT_ADMIN -> "not_admin";
                case NOT_FOUND -> "not_found";
                case SELF -> "self";
                case LAST_ADMIN -> "last_admin";
            };
            default -> "bad_action";
        };
        return AdminActions.redirect("/admin/security/accounts", notice);
    }

    private Row row(Member m, AuthMember admin, Set<Long> autoLocked) {
        boolean self = admin != null && admin.id() == m.getId();
        boolean manual = m.getStatus() == MemberStatus.LOCKED;
        boolean auto = autoLocked.contains(m.getId());
        String state = manual ? "잠김(관리자)" : auto ? "잠김(로그인 실패)" : m.getStatus() == MemberStatus.ACTIVE ? "활성" : "탈퇴";
        return new Row(m.getId(), m.getNickname(), m.getEmail(), m.getRole().name(), m.getTier().name(), state,
                service.lockable(m) && m.getStatus() == MemberStatus.ACTIVE && !self && !auto, manual || auto,
                m.getRole() == MemberRole.ADMIN && !self, service.overPrivileged(m), self);
    }

    private static String href(String q, String role, String status, String tier, int page) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/admin/security/accounts");
        if (q != null && !q.isBlank()) b.queryParam("q", q);
        return b.queryParam("role", parse(MemberRole.class, role) == null ? "all" : role).queryParam("status", status)
                .queryParam("tier", tier).queryParam("page", page).build().encode().toUriString();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return value == null || "all".equals(value) ? null : Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
