package com.san.bastionpay.admin;

import com.san.bastionpay.app.Pager;
import com.san.bastionpay.auth.AuthMember;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 관리자 보안 대시보드. 주소(/admin/**)와 메서드 양쪽에서 ADMIN 권한을 요구한다.
 * 화면은 읽기 전용이고, 쓰기는 잠긴 계정을 푸는 POST 하나뿐이다(CSRF 토큰 필요, 감사 기록에 남는다).
 */
@Controller
@RequestMapping("/admin/security")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSecurityController {

    private final SecurityDashboardService dashboard;
    private final MemberAdminService accounts;

    public AdminSecurityController(SecurityDashboardService dashboard, MemberAdminService accounts) {
        this.dashboard = dashboard;
        this.accounts = accounts;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "24h") String range, @RequestParam(name = "rp", defaultValue = "1") int reviewPage,
                       @RequestParam(required = false) String notice, Model model) {
        var r = SecurityDashboardService.Range.of(range);
        var overview = dashboard.load(r, reviewPage);
        model.addAttribute("d", overview);
        model.addAttribute("reviewPager", Pager.of(overview.reviews().page(), overview.reviews().totalPages(),
                p -> AdminActions.redirect("/admin/security", null, "range", r.code(), "rp", String.valueOf(p)).substring("redirect:".length())));
        model.addAttribute("ranges", SecurityDashboardService.Range.values());
        model.addAttribute("notice", noticeText(notice));
        model.addAttribute("activeNav", "admin");
        model.addAttribute("tab", "overview");
        return "admin/security";
    }

    @PostMapping("/accounts/{memberId}/unlock")
    public String unlock(@AuthenticationPrincipal AuthMember admin, @PathVariable long memberId,
                         @RequestParam(defaultValue = "24h") String range) {
        boolean unlocked = accounts.unlock(memberId, AdminActions.actor(admin)) == MemberAdminService.UnlockResult.UNLOCKED;
        return "redirect:" + UriComponentsBuilder.fromPath("/admin/security").queryParam("range", SecurityDashboardService.Range.of(range).code())
                .queryParam("notice", unlocked ? "unlocked" : "notlocked").build().encode().toUriString();
    }

    private static String noticeText(String notice) {
        if ("unlocked".equals(notice)) return "계정 잠금을 풀었습니다. 감사 기록에 남았습니다.";
        if ("notlocked".equals(notice)) return "이미 잠겨 있지 않은 계정입니다.";
        return null;
    }
}
