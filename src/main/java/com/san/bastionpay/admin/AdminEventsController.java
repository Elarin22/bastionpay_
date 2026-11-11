package com.san.bastionpay.admin;

import com.san.bastionpay.app.Pager;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.audit.Severity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

/** 관리자 화면: 보안 이벤트 목록. 기간·분류·종류·심각도·결과·출발지 IP·회원으로 걸러 10건씩 본다. */
@Controller
@RequestMapping("/admin/security/events")
@PreAuthorize("hasRole('ADMIN')")
public class AdminEventsController {

    private final SecurityEventSearchService search;

    public AdminEventsController(SecurityEventSearchService search) {
        this.search = search;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "24h") String range, @RequestParam(defaultValue = "all") String category,
                       @RequestParam(defaultValue = "all") String type, @RequestParam(defaultValue = "all") String severity,
                       @RequestParam(defaultValue = "all") String outcome, @RequestParam(required = false) String ip,
                       @RequestParam(required = false) String member, @RequestParam(defaultValue = "1") int page, Model model) {
        var r = SecurityDashboardService.Range.of(range);
        var cat = AdminParams.enumOf(SecurityEventType.Category.class, category);
        var evType = AdminParams.enumOf(SecurityEventType.class, type);
        String sev = SecurityEventSearchService.HIGH_PLUS.equalsIgnoreCase(severity) ? SecurityEventSearchService.HIGH_PLUS
                : AdminParams.enumOf(Severity.class, severity) == null ? null : AdminParams.enumOf(Severity.class, severity).name();
        var out = AdminParams.enumOf(EventOutcome.class, outcome);
        boolean ipGiven = ip != null && !ip.isBlank();
        var normalizedIp = ipGiven ? AdminParams.ip(ip) : java.util.Optional.<String>empty();
        boolean memberGiven = member != null && !member.isBlank();
        var memberId = memberGiven ? AdminParams.memberId(member) : java.util.Optional.<Long>empty();

        // 형식이 틀린 IP·회원 번호는 조건을 무시하지 않고 결과를 비운다(전체가 나와서 오해하지 않게)
        boolean invalid = (ipGiven && normalizedIp.isEmpty()) || (memberGiven && memberId.isEmpty());
        PagedList<SecurityDashboardService.EventRow> result = invalid ? PagedList.of(java.util.List.of(), 1)
                : search.search(new SecurityEventSearchService.Filter(r, cat, evType, sev, out, normalizedIp.orElse(null), memberId.orElse(null)), page);

        Map<String, String> keep = new LinkedHashMap<>();
        keep.put("range", r.code());
        keep.put("category", cat == null ? null : cat.name());
        keep.put("type", evType == null ? null : evType.name());
        keep.put("severity", sev);
        keep.put("outcome", out == null ? null : out.name());
        keep.put("ip", ipGiven ? ip.strip() : null);
        keep.put("member", memberGiven ? member.strip() : null);

        model.addAttribute("result", result);
        model.addAttribute("pager", Pager.of(result.page(), result.totalPages(), p -> AdminParams.href("/admin/security/events", keep, p)));
        model.addAttribute("ranges", SecurityDashboardService.Range.values());
        model.addAttribute("categories", SecurityEventType.Category.values());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("outcomes", EventOutcome.values());
        model.addAttribute("range", r.code());
        model.addAttribute("category", cat == null ? "all" : cat.name());
        model.addAttribute("type", evType == null ? "all" : evType.name());
        model.addAttribute("severity", sev == null ? "all" : sev);
        model.addAttribute("outcome", out == null ? "all" : out.name());
        model.addAttribute("ip", ipGiven ? ip.strip() : "");
        model.addAttribute("member", memberGiven ? member.strip() : "");
        model.addAttribute("invalid", invalid);
        model.addAttribute("activeNav", "admin");
        model.addAttribute("tab", "events");
        return "admin/events";
    }
}
