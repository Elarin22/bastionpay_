package com.san.bastionpay.openbanking;

import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.auth.JwtProperties;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.core.util.LogSanitizer;
import com.san.bastionpay.openbanking.OpenBankingClient.Account;
import com.san.bastionpay.openbanking.OpenBankingClient.Balance;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
public class OpenBankingController {

    private static final String STATE_COOKIE = "ob_state";
    private static final String CALLBACK_PATH = "/openbanking";

    /** 쿼리로 받은 결과 키는 이 목록에 있는 것만 문구로 바꿔 보여 준다(입력을 그대로 출력하지 않는다). */
    private static final Map<String, String> RESULTS = Map.of(
            "ok", "오픈뱅킹 계좌를 연결했습니다.",
            "cancelled", "연결이 취소되었습니다. 필요하면 다시 시도해 주세요.",
            "disconnected", "오픈뱅킹 연결을 해제했습니다.",
            "duplicate", ErrorCode.OPENBANKING_ALREADY_LINKED.message());

    private final OpenBankingService service;
    private final JwtProperties jwt;
    private final Duration stateTtl;
    private final RedirectUris redirectUris;

    public OpenBankingController(OpenBankingService service, JwtProperties jwt, OpenBankingProperties props,
                                 RedirectUris redirectUris) {
        this.redirectUris = redirectUris;
        this.service = service;
        this.jwt = jwt;
        this.stateTtl = props.stateTtl();
    }

    @GetMapping("/connect")
    public String connect(@AuthenticationPrincipal AuthMember member, @RequestParam(required = false) String result, Model model) {
        fillConnectModel(member, model);
        model.addAttribute("result", result == null ? null : RESULTS.get(result));
        return "app/connect";
    }

    /**
     * 은행 인증 화면으로 보낸다. 폼 제출이 아니라 링크 이동으로 시작한다:
     * CSP form-action 'self' 가 다른 사이트로 가는 리다이렉트를 막기 때문이다.
     */
    @GetMapping("/openbanking/authorize")
    public String authorize(@AuthenticationPrincipal AuthMember member, HttpServletRequest request, HttpServletResponse response) {
        OpenBankingService.Authorization auth = service.begin(member.id(), redirectUris.forRequest(request));
        // 은행에서 돌아오는 요청에도 실려야 하므로 SameSite=Lax. 이 경로에서만 보낸다
        addStateCookie(response, auth.state(), stateTtl);
        return "redirect:" + auth.url();
    }

    /**
     * 은행이 돌려보내는 곳. 다른 사이트에서 넘어온 요청이라 로그인 쿠키(Strict)가 실리지 않는다.
     * 그래서 인증 없이 열어 두고, 누구의 요청인지는 서버에 저장한 state 로 확인한다.
     * 결과 화면은 같은 사이트에서 이동(meta refresh)해 로그인 쿠키가 다시 실리게 한다.
     */
    @GetMapping("/openbanking/callback")
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error,
                           @CookieValue(name = STATE_COOKIE, required = false) String stateCookie,
                           HttpServletRequest request, HttpServletResponse response, Model model) {
        addStateCookie(response, "", Duration.ZERO);
        if (error != null) {
            // 사용자가 취소했거나 은행이 거절한 경우
            log.info("openbanking callback without code error={}", LogSanitizer.clean(error));
            model.addAttribute("target", "/connect?result=cancelled");
            return "app/openbanking-callback";
        }
        try {
            service.complete(state, stateCookie, code, redirectUris.forRequest(request));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.OPENBANKING_ALREADY_LINKED) {
                throw e;
            }
            model.addAttribute("target", "/connect?result=duplicate");
            return "app/openbanking-callback";
        }
        model.addAttribute("target", "/connect?result=ok");
        return "app/openbanking-callback";
    }

    @PostMapping("/openbanking/disconnect")
    public String disconnect(@AuthenticationPrincipal AuthMember member) {
        service.disconnect(member.id());
        return "redirect:/connect?result=disconnected";
    }

    private void fillConnectModel(AuthMember member, Model model) {
        model.addAttribute("activeNav", "connect");
        model.addAttribute("hideSampleNote", true);   // 이 화면은 실제 연결 상태를 보여 준다(예시 데이터 안내 띠를 숨긴다)
        model.addAttribute("configured", service.isConfigured());
        model.addAttribute("balanceEnabled", service.canQueryBalance());
        boolean linked = service.isLinked(member.id());
        List<OpenBankingService.AccountBalance> rows = List.of();
        if (linked) {
            try {
                rows = service.accountsWithBalance(member.id());
            } catch (BusinessException e) {
                log.warn("openbanking accounts failed memberId={} code={} detail={}", member.id(), e.getErrorCode().code(), e.getMessage());
                if (e.getErrorCode() == ErrorCode.OPENBANKING_NOT_LINKED) {
                    linked = false;   // 토큰을 읽을 수 없으면 다시 연결하도록 안내한다
                } else {
                    model.addAttribute("error", e.getErrorCode().message());
                }
            }
        }
        model.addAttribute("linked", linked);
        model.addAttribute("rows", rows);
    }

    private void addStateCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true).secure(jwt.cookie().secure()).sameSite("Lax")
                .path(CALLBACK_PATH).maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
