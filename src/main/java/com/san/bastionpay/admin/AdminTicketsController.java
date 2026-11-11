package com.san.bastionpay.admin;

import com.san.bastionpay.audit.IpAddresses;
import com.san.bastionpay.audit.Severity;
import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.app.Pager;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 관리자 화면: 인시던트 티켓 목록·생성·종료. */
@Controller
@RequestMapping("/admin/security/tickets")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTicketsController {

    static final int PAGE_SIZE = 10;
    /** 티켓에 쓸 수 있는 심각도(INFO 는 티켓 대상이 아니다). */
    static final Set<Severity> TICKET_SEVERITIES = EnumSet.of(Severity.LOW, Severity.MEDIUM, Severity.HIGH, Severity.CRITICAL);

    private static final Map<String, String> NOTICES = Map.of(
            "created", "티켓을 만들었습니다.",
            "updated", "이미 열린 티켓이 있어 내용을 갱신했습니다.",
            "closed", "티켓을 종료했습니다.",
            "none", "종료할 수 있는 티켓이 없습니다(이미 종료됐거나 선택하지 않았습니다).",
            "bad_ip", "출발지 IP 형식이 올바르지 않습니다.");

    private final IncidentService incidents;

    public AdminTicketsController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "all") String status, @RequestParam(defaultValue = "all") String severity,
                       @RequestParam(required = false) String ip, @RequestParam(defaultValue = "1") int page,
                       @RequestParam(required = false) String notice, @RequestParam(required = false) String closedCount, Model model) {
        Incident.Status st = parseStatus(status);
        Severity sev = parseSeverity(severity);
        Page<Incident> result = incidents.list(st, sev, ip, page, PAGE_SIZE);
        model.addAttribute("tickets", result.getContent().stream().map(AdminTicketsController::row).toList());
        model.addAttribute("total", result.getTotalElements());
        model.addAttribute("pager", Pager.of(Math.max(page, 1), result.getTotalPages(), p -> href(status, severity, ip, p)));
        model.addAttribute("counts", incidents.counts());
        model.addAttribute("status", st == null ? "all" : st.name());
        model.addAttribute("severity", sev == null ? "all" : sev.name());
        model.addAttribute("severities", TICKET_SEVERITIES);
        model.addAttribute("ip", ip == null ? "" : ip.strip());
        model.addAttribute("notice", notice == null ? null : NOTICES.get(notice));
        model.addAttribute("closedCount", closedCount);
        model.addAttribute("activeNav", "admin");
        model.addAttribute("tab", "tickets");
        return "admin/tickets";
    }

    /** 티켓 만들기. 같은 IP 의 열린 티켓이 있으면 그 티켓을 갱신한다. */
    @PostMapping
    public String create(@AuthenticationPrincipal AuthMember admin, @RequestParam String ip, @RequestParam(required = false) String title,
                         @RequestParam(defaultValue = "auto") String severity, @RequestParam(required = false) Integer hours) {
        String normalized = IpAddresses.normalize(ip).orElse(null);
        if (normalized == null) return AdminActions.redirect("/admin/security/tickets", "bad_ip");
        Severity sev = TICKET_SEVERITIES.contains(parseSeverity(severity)) ? parseSeverity(severity) : null;
        var result = incidents.create(normalized, title, sev, hours, AdminActions.actor(admin));
        return AdminActions.redirect("/admin/security/tickets", result.created() ? "created" : "updated");
    }

    /** 고른 티켓(들)을 종료한다. */
    @PostMapping("/close")
    public String close(@AuthenticationPrincipal AuthMember admin, @RequestParam(required = false) List<Long> ids) {
        int closed = ids == null ? 0 : incidents.closeMany(ids, AdminActions.actor(admin));
        return AdminActions.redirect("/admin/security/tickets", closed > 0 ? "closed" : "none", "closedCount", closed > 0 ? String.valueOf(closed) : null);
    }

    public record TicketRow(long id, String title, String ip, String severity, boolean open, int events, String actions, String updated,
                            String createdBy, String closedBy, String summary) {}

    private static TicketRow row(Incident i) {
        return new TicketRow(i.getId(), i.getTitle(), i.getSrcIp(), i.getSeverity().name(), i.isOpen(), i.getEventCount(),
                i.getActions() == null ? "-" : i.getActions(), SecurityDashboardService.kst(i.getUpdatedAt()), i.getCreatedBy(),
                i.getClosedBy(), i.getSummary());
    }

    /** 열린 티켓을 모두 종료한다(심각도를 고르면 그 심각도의 열린 티켓만). 화면에서는 한 번 더 확인한 뒤에만 이 주소로 온다. */
    @PostMapping("/close-all")
    public String closeAll(@AuthenticationPrincipal AuthMember admin, @RequestParam(defaultValue = "all") String severity) {
        int closed = incidents.closeAllOpen(parseSeverity(severity), AdminActions.actor(admin));
        return AdminActions.redirect("/admin/security/tickets", closed > 0 ? "closed" : "none", "closedCount", closed > 0 ? String.valueOf(closed) : null);
    }

    private static String href(String status, String severity, String ip, int page) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/admin/security/tickets").queryParam("status", parseStatus(status) == null ? "all" : status)
                .queryParam("severity", parseSeverity(severity) == null ? "all" : severity);
        if (ip != null && !ip.isBlank()) b.queryParam("ip", ip.strip());
        return b.queryParam("page", page).build().encode().toUriString();
    }

    static Incident.Status parseStatus(String value) {
        try {
            return value == null || "all".equals(value) ? null : Incident.Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Severity parseSeverity(String value) {
        try {
            return value == null || "all".equals(value) || "auto".equals(value) ? null : Severity.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
