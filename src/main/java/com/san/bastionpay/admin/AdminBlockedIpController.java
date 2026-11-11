package com.san.bastionpay.admin;

import com.san.bastionpay.app.Pager;
import com.san.bastionpay.auth.AuthMember;
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

import java.util.Map;

/** 관리자 화면: 차단된 IP 목록·차단·해제. 지금 접속한 내 IP 는 차단할 수 없다. */
@Controller
@RequestMapping("/admin/security/blocked-ips")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBlockedIpController {

    static final int PAGE_SIZE = 10;
    private static final Map<String, String> NOTICES = Map.of(
            "blocked", "IP 를 차단했습니다. 이 IP 의 요청은 앱에 닿기 전에 403 으로 거부됩니다.",
            "already", "이미 차단된 IP 입니다. 처음 차단한 시각과 사유는 그대로 둡니다.",
            "invalid", "IP 형식이 올바르지 않습니다. 호스트 이름이 아니라 IP 주소를 입력해 주세요.",
            "self", "지금 접속 중인 내 IP 는 차단할 수 없습니다. 차단하면 관리자 화면도 쓸 수 없게 됩니다.",
            "unblocked", "차단을 해제했습니다.",
            "notfound", "차단 목록에 없는 IP 입니다.");

    private final BlockedIpService blocked;

    public AdminBlockedIpController(BlockedIpService blocked) {
        this.blocked = blocked;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
                       @RequestParam(required = false) String notice, Model model) {
        Page<BlockedIp> result = blocked.list(q, page, PAGE_SIZE);
        model.addAttribute("rows", result.getContent().stream().map(b -> new Row(b.getIp(), b.getReason(), b.getBlockedBy(), SecurityDashboardService.kst(b.getBlockedAt()))).toList());
        model.addAttribute("total", result.getTotalElements());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("pager", Pager.of(Math.max(page, 1), result.getTotalPages(), p -> href(q, p)));
        model.addAttribute("myIp", AdminActions.requesterIp());
        model.addAttribute("notice", notice == null ? null : NOTICES.get(notice));
        model.addAttribute("activeNav", "admin");
        model.addAttribute("tab", "blocked");
        return "admin/blocked-ips";
    }

    public record Row(String ip, String reason, String blockedBy, String blockedAt) {}

    @PostMapping
    public String block(@AuthenticationPrincipal AuthMember admin, @RequestParam String ip, @RequestParam(required = false) String reason) {
        var result = blocked.block(ip, reason, AdminActions.actor(admin), AdminActions.requesterIp());
        return AdminActions.redirect("/admin/security/blocked-ips", switch (result) {
            case BLOCKED -> "blocked";
            case ALREADY_BLOCKED -> "already";
            case INVALID_IP -> "invalid";
            case SELF -> "self";
        });
    }

    @PostMapping("/unblock")
    public String unblock(@AuthenticationPrincipal AuthMember admin, @RequestParam String ip) {
        return AdminActions.redirect("/admin/security/blocked-ips", blocked.unblock(ip, AdminActions.actor(admin)) ? "unblocked" : "notfound");
    }

    private static String href(String q, int page) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/admin/security/blocked-ips");
        if (q != null && !q.isBlank()) b.queryParam("q", q);
        return b.queryParam("page", page).build().encode().toUriString();
    }
}
