package com.san.bastionpay.admin;

import com.san.bastionpay.app.Pager;
import com.san.bastionpay.audit.SecurityEventType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

/** 관리자 화면: 집계. 같은 종류의 이벤트를 IP·로그인 계정·회원별로 많은 순으로 보고, 최소 횟수로 걸러 10건씩 넘긴다. */
@Controller
@RequestMapping("/admin/security/stats")
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatsController {

    private final SecurityStatsService stats;

    public AdminStatsController(SecurityStatsService stats) {
        this.stats = stats;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "24h") String range, @RequestParam(defaultValue = "ip") String by,
                       @RequestParam(defaultValue = "LOGIN_FAILURE") String type, @RequestParam(defaultValue = "1") String min,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        var r = SecurityDashboardService.Range.of(range);
        var group = AdminParams.enumOf(SecurityStatsService.By.class, by);
        if (group == null) group = SecurityStatsService.By.IP;
        var evType = AdminParams.enumOf(SecurityEventType.class, type);
        if (evType == null) evType = SecurityEventType.LOGIN_FAILURE;
        long minCount = AdminParams.memberId(min).orElse(1L);   // 1 이상의 정수만(그 밖은 1)
        if (minCount < 1) minCount = 1;

        var result = stats.groups(group, evType, r, minCount, page);
        Map<String, String> keep = new LinkedHashMap<>();
        keep.put("range", r.code());
        keep.put("by", group.name().toLowerCase());
        keep.put("type", evType.name());
        keep.put("min", String.valueOf(minCount));

        model.addAttribute("result", result);
        model.addAttribute("pager", Pager.of(result.page(), result.totalPages(), p -> AdminParams.href("/admin/security/stats", keep, p)));
        model.addAttribute("ranges", SecurityDashboardService.Range.values());
        model.addAttribute("categories", SecurityEventType.Category.values());
        model.addAttribute("range", r.code());
        model.addAttribute("by", group.name().toLowerCase());
        model.addAttribute("type", evType.name());
        model.addAttribute("min", minCount);
        model.addAttribute("activeNav", "admin");
        model.addAttribute("tab", "stats");
        return "admin/stats";
    }
}
