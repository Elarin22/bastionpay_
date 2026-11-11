package com.san.bastionpay.auth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** 권한·등급 검증용. 테스트 클래스패스에만 있다. */
@Controller
class AccessTestController {

    @GetMapping("/admin/test-ping")
    @ResponseBody
    String admin() {
        return "admin-ok";
    }

    @GetMapping("/test-tier/standard")
    @ResponseBody
    @PreAuthorize("hasAuthority('TIER_STANDARD')")
    String standard() {
        return "standard-ok";
    }

    @GetMapping("/test-tier/vip")
    @ResponseBody
    @PreAuthorize("hasAuthority('TIER_VIP')")
    String vip() {
        return "vip-ok";
    }

    @GetMapping("/test-tier/vvip")
    @ResponseBody
    @PreAuthorize("hasAuthority('TIER_VVIP')")
    String vvip() {
        return "vvip-ok";
    }
}
