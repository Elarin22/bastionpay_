package com.san.bastionpay.admin;

import com.san.bastionpay.app.Pager;
import com.san.bastionpay.audit.AuditLog;
import com.san.bastionpay.audit.SecurityEventType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 관리자 화면: 감사 기록. 누가(시스템·관리자·API) 무엇을 했는지를 걸러 10건씩 본다. */
@Controller
@RequestMapping("/admin/security/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    /** 감사 기록에 남는 조치 종류(대응 조치). */
    private static final List<SecurityEventType> ACTIONS = List.of(SecurityEventType.ACCOUNT_LOCKED, SecurityEventType.ACCOUNT_UNLOCKED,
            SecurityEventType.ROLE_REVOKED, SecurityEventType.TIER_CHANGED, SecurityEventType.IP_BLOCKED, SecurityEventType.IP_UNBLOCKED,
            SecurityEventType.INCIDENT_CREATED, SecurityEventType.INCIDENT_CLOSED);

    private final AuditSearchService search;

    public AdminAuditController(AuditSearchService search) {
        this.search = search;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "7d") String range, @RequestParam(defaultValue = "all") String actor,
                       @RequestParam(defaultValue = "all") String action, @RequestParam(required = false) String target,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        var r = SecurityDashboardService.Range.of(range);
        var actorType = AdminParams.enumOf(AuditLog.ActorType.class, actor);
        var act = AdminParams.enumOf(SecurityEventType.class, action);
        boolean targetGiven = target != null && !target.isBlank();
        var targetId = targetGiven ? AdminParams.memberId(target) : java.util.Optional.<Long>empty();
        boolean invalid = targetGiven && targetId.isEmpty();

        PagedList<AuditSearchService.Row> result = invalid ? PagedList.of(List.of(), 1)
                : search.search(new AuditSearchService.Filter(r, actorType, act, targetId.orElse(null)), page);
        Map<String, String> keep = new LinkedHashMap<>();
        keep.put("range", r.code());
        keep.put("actor", actorType == null ? null : actorType.name());
        keep.put("action", act == null ? null : act.name());
        keep.put("target", targetGiven ? target.strip() : null);

        model.addAttribute("result", result);
        model.addAttribute("pager", Pager.of(result.page(), result.totalPages(), p -> AdminParams.href("/admin/security/audit", keep, p)));
        model.addAttribute("ranges", SecurityDashboardService.Range.values());
        model.addAttribute("actors", AuditLog.ActorType.values());
        model.addAttribute("actions", ACTIONS);
        model.addAttribute("range", r.code());
        model.addAttribute("actor", actorType == null ? "all" : actorType.name());
        model.addAttribute("action", act == null ? "all" : act.name());
        model.addAttribute("target", targetGiven ? target.strip() : "");
        model.addAttribute("invalid", invalid);
        model.addAttribute("activeNav", "admin");
        model.addAttribute("tab", "audit");
        return "admin/audit";
    }
}
