package com.san.bastionpay.app;

import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 후 대시보드 화면. 인증은 SecurityConfig 의 anyRequest().authenticated() 가 맡는다.
 * 예금·적금·가상계좌는 예시(하드코딩) 데이터뿐이라 화면을 뺐다. 경로가 없으므로 직접 입력해도 404 가 된다.
 */
@Slf4j
@Controller
public class AppPageController {

    private final OpenBankingService openBanking;

    public AppPageController(OpenBankingService openBanking) {
        this.openBanking = openBanking;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AuthMember member, Model model) {
        // 대시보드는 오픈뱅킹으로 연결한 실제 계좌의 합계만 보여 준다. 예금·적금·가상계좌는 아직 연동 전이라 싣지 않는다
        model.addAttribute("activeNav", "dashboard");
        model.addAttribute("hideSampleNote", true);
        model.addAttribute("configured", openBanking.isConfigured());
        boolean linked = openBanking.isLinked(member.id());
        if (linked) {
            try {
                model.addAttribute("summary", openBanking.summary(member.id()));
            } catch (BusinessException e) {
                log.warn("dashboard summary failed memberId={} code={} detail={}", member.id(), e.getErrorCode().code(), e.getMessage());
                if (e.getErrorCode() == ErrorCode.OPENBANKING_NOT_LINKED) {
                    linked = false;
                } else {
                    model.addAttribute("error", e.getErrorCode().message());
                }
            }
        }
        model.addAttribute("linked", linked);
        return "app/dashboard";
    }
}
