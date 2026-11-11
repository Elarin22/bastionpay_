package com.san.mockbank.oauth;

import com.san.mockbank.account.BankAccount;
import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.phone.PhoneAuthException;
import com.san.mockbank.phone.PhoneAuthService;
import com.san.mockbank.phone.PhoneNumbers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 사용자 인증 화면: 요청 검증 → 휴대폰 번호 입력 → 문자 인증번호 확인 → 연결할 계좌 선택·동의 → 인증 코드와 함께 이용기관으로 돌려보냄.
 * 진행 중인 요청은 세션에 보관한다. 인증번호를 확인하면 세션 ID 를 새로 발급한다(세션 고정 방지).
 */
@Slf4j
@Controller
public class AuthorizeController {

    private static final String REQUEST = "authorizeRequest";
    private static final String CUSTOMER = "authorizeCustomerId";
    private static final String PHONE = "authorizePhone";
    private static final String VERIFICATION = "authorizeVerificationId";
    private static final String DEV_CODE = "authorizeDevCode";
    private static final String EXPIRES_AT = "authorizeCodeExpiresAt";

    private final OauthService oauth;
    private final PhoneAuthService phoneAuth;

    public AuthorizeController(OauthService oauth, PhoneAuthService phoneAuth) {
        this.oauth = oauth;
        this.phoneAuth = phoneAuth;
    }

    @GetMapping("/oauth/2.0/authorize")
    public String authorize(@RequestParam(name = "response_type", required = false) String responseType,
                            @RequestParam(name = "client_id", required = false) String clientId,
                            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
                            @RequestParam(required = false) String scope,
                            @RequestParam(required = false) String state,
                            HttpServletRequest request, HttpServletResponse response, Model model) {
        AuthorizeRequest authRequest = oauth.validateAuthorizeRequest(responseType, clientId, redirectUri, scope, state);
        HttpSession session = request.getSession();
        // 이전 인증 진행 상태는 모두 버린다
        for (String key : new String[]{CUSTOMER, PHONE, VERIFICATION, DEV_CODE, EXPIRES_AT}) session.removeAttribute(key);
        session.setAttribute(REQUEST, authRequest);
        return phonePage(authRequest, response, model, null);
    }

    /** 휴대폰 번호를 받아 인증번호를 문자로 보낸다. */
    @PostMapping("/oauth/2.0/authorize/phone")
    public String phone(@RequestParam(required = false) String phone, HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpSession session = request.getSession(false);
        AuthorizeRequest authRequest = authRequest(session);
        try {
            storeStarted(session, phoneAuth.start(phone));
            return "redirect:/oauth/2.0/authorize/code";
        } catch (PhoneAuthException e) {
            return phonePage(authRequest, response, model, e.getMessage());
        }
    }

    @GetMapping("/oauth/2.0/authorize/code")
    public String codePage(HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpSession session = request.getSession(false);
        AuthorizeRequest authRequest = authRequest(session);
        if (session.getAttribute(VERIFICATION) == null) throw new AuthorizeException("먼저 휴대폰 번호를 입력해 주세요.");
        return codeView(session, authRequest, response, model, null);
    }

    @PostMapping("/oauth/2.0/authorize/code")
    public String code(@RequestParam(required = false) String code, HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpSession session = request.getSession(false);
        AuthorizeRequest authRequest = authRequest(session);
        Long verificationId = (Long) session.getAttribute(VERIFICATION);
        String phone = (String) session.getAttribute(PHONE);
        if (verificationId == null || phone == null) throw new AuthorizeException("먼저 휴대폰 번호를 입력해 주세요.");
        try {
            BankCustomer customer = phoneAuth.verify(verificationId, phone, code);
            request.changeSessionId();
            for (String key : new String[]{PHONE, VERIFICATION, DEV_CODE, EXPIRES_AT}) session.removeAttribute(key);
            session.setAttribute(CUSTOMER, customer.getId());
            return "redirect:/oauth/2.0/authorize/consent";
        } catch (PhoneAuthException e) {
            if (e.isFinished()) {
                session.invalidate();
                throw new AuthorizeException(e.getMessage());
            }
            return codeView(session, authRequest, response, model, e.getMessage());
        }
    }

    /** 인증번호를 다시 받는다(재발송 간격·시간당 횟수 제한이 있다). */
    @PostMapping("/oauth/2.0/authorize/resend")
    public String resend(HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpSession session = request.getSession(false);
        AuthorizeRequest authRequest = authRequest(session);
        String phone = (String) session.getAttribute(PHONE);
        if (phone == null) throw new AuthorizeException("먼저 휴대폰 번호를 입력해 주세요.");
        try {
            storeStarted(session, phoneAuth.start(phone));
            return codeView(session, authRequest, response, model, null);
        } catch (PhoneAuthException e) {
            return codeView(session, authRequest, response, model, e.getMessage());
        }
    }

    @GetMapping("/oauth/2.0/authorize/consent")
    public String consentPage(HttpServletRequest request, HttpServletResponse response, Model model) {
        Session s = requireVerified(request);
        List<BankAccount> accounts = oauth.selectableAccounts(s.customerId());
        model.addAttribute("accounts", accounts);
        model.addAttribute("wantsTransfer", s.request().wantsTransfer());
        model.addAttribute("scopeLabels", s.request().scopeLabels());
        model.addAttribute("clientName", s.request().clientName());
        applyCsp(response, s.request());
        return "authorize/consent";
    }

    @PostMapping("/oauth/2.0/authorize/consent")
    public String consent(@RequestParam(name = "accountId", required = false) List<Long> accountIds,
                          @RequestParam(name = "transferId", required = false) List<Long> transferIds,
                          HttpServletRequest request, HttpServletResponse response, Model model) {
        Session s = requireVerified(request);
        try {
            String code = oauth.issueCode(s.request(), s.customerId(),
                    accountIds == null ? Set.of() : new HashSet<>(accountIds),
                    transferIds == null ? Set.of() : new HashSet<>(transferIds));
            request.getSession().invalidate();
            return "redirect:" + OauthService.successRedirect(s.request(), code);
        } catch (AuthorizeException e) {
            // 계좌를 고르지 않은 경우처럼 다시 선택하면 되는 오류
            model.addAttribute("accounts", oauth.selectableAccounts(s.customerId()));
            model.addAttribute("wantsTransfer", s.request().wantsTransfer());
            model.addAttribute("scopeLabels", s.request().scopeLabels());
            model.addAttribute("clientName", s.request().clientName());
            model.addAttribute("error", e.getMessage());
            applyCsp(response, s.request());
            return "authorize/consent";
        }
    }

    /** 사용자가 취소하면 코드 없이 access_denied 로 돌려보낸다. */
    @PostMapping("/oauth/2.0/authorize/cancel")
    public String cancel(HttpServletRequest request) {
        AuthorizeRequest authRequest = authRequest(request.getSession(false));
        request.getSession().invalidate();
        return "redirect:" + OauthService.deniedRedirect(authRequest);
    }

    @ExceptionHandler(AuthorizeException.class)
    String authorizeError(AuthorizeException e, HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("message", e.getMessage());
        applyCsp(response, null);
        return "authorize/error";
    }

    private void storeStarted(HttpSession session, PhoneAuthService.Started started) {
        session.setAttribute(PHONE, started.phone());
        session.setAttribute(VERIFICATION, started.verificationId());
        session.setAttribute(EXPIRES_AT, started.expiresAt());
        if (started.devCode() != null) session.setAttribute(DEV_CODE, started.devCode());
        else session.removeAttribute(DEV_CODE);
    }

    private String phonePage(AuthorizeRequest authRequest, HttpServletResponse response, Model model, String error) {
        model.addAttribute("error", error);
        model.addAttribute("scopeLabels", authRequest.scopeLabels());
        model.addAttribute("clientName", authRequest.clientName());
        applyCsp(response, authRequest);
        return "authorize/phone";
    }

    private String codeView(HttpSession session, AuthorizeRequest authRequest, HttpServletResponse response, Model model, String error) {
        LocalDateTime expiresAt = (LocalDateTime) session.getAttribute(EXPIRES_AT);
        long seconds = expiresAt == null ? 0 : Math.max(0, Duration.between(LocalDateTime.now(), expiresAt).toSeconds());
        model.addAttribute("error", error);
        model.addAttribute("maskedPhone", PhoneNumbers.mask((String) session.getAttribute(PHONE)));
        model.addAttribute("devCode", session.getAttribute(DEV_CODE));
        model.addAttribute("remainingMinutes", Math.max(1, (seconds + 59) / 60));
        model.addAttribute("clientName", authRequest.clientName());
        applyCsp(response, authRequest);
        return "authorize/code";
    }

    private static AuthorizeRequest authRequest(HttpSession session) {
        AuthorizeRequest authRequest = session == null ? null : (AuthorizeRequest) session.getAttribute(REQUEST);
        if (authRequest == null) throw new AuthorizeException("인증 요청이 만료되었습니다. 이용기관 화면에서 다시 시작해 주세요.");
        return authRequest;
    }

    private Session requireVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        AuthorizeRequest authRequest = session == null ? null : (AuthorizeRequest) session.getAttribute(REQUEST);
        Long customerId = session == null ? null : (Long) session.getAttribute(CUSTOMER);
        if (authRequest == null || customerId == null) {
            throw new AuthorizeException("본인 인증이 필요하거나 인증 요청이 만료되었습니다. 이용기관 화면에서 다시 시작해 주세요.");
        }
        return new Session(authRequest, customerId);
    }

    /**
     * 외부 스크립트·스타일을 막는다. 동의·취소 후 이용기관 주소로 돌려보내는 이동은
     * 브라우저의 form-action 검사 대상이라 그 주소의 출처만 따로 허용한다.
     */
    private static void applyCsp(HttpServletResponse response, AuthorizeRequest authRequest) {
        String formAction = authRequest == null ? "'self'" : "'self' " + authRequest.redirectOrigin();
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'none'; style-src 'self'; "
                + "img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action " + formAction);
    }

    private record Session(AuthorizeRequest request, Long customerId) {
    }
}
