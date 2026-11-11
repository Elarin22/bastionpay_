package com.san.bastionpay.signup;

import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.auth.AuthCookies;
import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.auth.AuthService;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.signup.SignupForms.CodeForm;
import com.san.bastionpay.signup.SignupForms.EmailForm;
import com.san.bastionpay.signup.SignupForms.PasswordForm;
import com.san.bastionpay.signup.SignupForms.ProfileForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Duration;
import java.util.Optional;

/**
 * 회원가입 마법사. 현재 단계는 URL 이 아니라 서버(DB)의 진행 상태로 결정하므로
 * 단계를 건너뛰거나 잠긴 값을 다시 제출해도 서버가 거부한다.
 * 오류는 리다이렉트(세션 플래시) 대신 같은 화면에 바로 다시 그린다. 세션을 쓰지 않기 위해서다.
 */
@Slf4j
@Controller
public class SignupController {

    private static final String COOKIE = "signup_token";
    private static final String VIEW = "auth/signup";

    private final SignupService signupService;
    private final AuthService authService;
    private final AuthCookies cookies;
    private final SignupProperties props;
    private final SecurityEventRecorder events;

    public SignupController(SignupService signupService, AuthService authService,
                            AuthCookies cookies, SignupProperties props, SecurityEventRecorder events) {
        this.events = events;
        this.signupService = signupService;
        this.authService = authService;
        this.cookies = cookies;
        this.props = props;
    }

    @GetMapping("/signup")
    public String page(@CookieValue(name = COOKIE, required = false) String token,
                       Authentication authentication, Model model) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthMember) return "redirect:/dashboard";
        return render(token, model, null);
    }

    /** 1단계: 이메일 입력 → 인증 메일 발송 (화면에서는 전송 중 로딩 표시) */
    @PostMapping("/signup/email")
    public String email(@CookieValue(name = COOKIE, required = false) String token,
                        @Valid @ModelAttribute EmailForm form, BindingResult binding,
                        HttpServletResponse response, Model model) {
        if (binding.hasErrors()) return fail(token, model, response, firstError(binding));
        try {
            String newToken = signupService.start(token, form.email());
            cookies.add(response, cookies.build(COOKIE, newToken, "/signup", props.requestTtl()));
        } catch (AlreadyRegisteredException e) {
            // 이미 가입한 이메일이면 가입 화면에 머물지 않고 로그인 화면으로 보낸다(이메일은 주소에 싣지 않는다)
            return "redirect:/login?registered";
        } catch (SignupException e) {
            return fail(token, model, response, e.getMessage());
        }
        return "redirect:/signup";
    }

    @PostMapping("/signup/code")
    public String code(@CookieValue(name = COOKIE, required = false) String token,
                       @Valid @ModelAttribute CodeForm form, BindingResult binding,
                       HttpServletResponse response, Model model) {
        if (binding.hasErrors()) return fail(token, model, response, firstError(binding));
        try {
            signupService.verifyCode(token, form.code());
        } catch (SignupException e) {
            return fail(token, model, response, e.getMessage());
        }
        return "redirect:/signup";
    }

    @PostMapping("/signup/resend")
    public String resend(@CookieValue(name = COOKIE, required = false) String token,
                         HttpServletResponse response, Model model) {
        try {
            signupService.resend(token);
        } catch (AlreadyRegisteredException e) {
            cookies.add(response, cookies.build(COOKIE, "", "/signup", Duration.ZERO));
            return "redirect:/login?registered";
        } catch (SignupException e) {
            return fail(token, model, response, e.getMessage());
        }
        return "redirect:/signup";
    }

    /** 이메일 인증 전에만 가능. 인증 후에는 서비스가 거부한다. */
    @PostMapping("/signup/restart")
    public String restart(@CookieValue(name = COOKIE, required = false) String token,
                          HttpServletResponse response, Model model) {
        try {
            signupService.restart(token);
        } catch (SignupException e) {
            return fail(token, model, response, e.getMessage());
        }
        cookies.add(response, cookies.build(COOKIE, "", "/signup", Duration.ZERO));
        return "redirect:/signup";
    }

    @PostMapping("/signup/profile")
    public String profile(@CookieValue(name = COOKIE, required = false) String token,
                          @Valid @ModelAttribute ProfileForm form, BindingResult binding,
                          HttpServletResponse response, Model model) {
        if (binding.hasErrors()) return fail(token, model, response, firstError(binding));
        try {
            signupService.saveProfile(token, form.name().trim(), form.nickname());
        } catch (SignupException e) {
            return fail(token, model, response, e.getMessage());
        }
        return "redirect:/signup";
    }

    /** 비밀번호 2단 입력: 정책 검사 → 두 입력 일치 확인 → 잠금 */
    @PostMapping("/signup/password")
    public String password(@CookieValue(name = COOKIE, required = false) String token,
                           @Valid @ModelAttribute PasswordForm form, BindingResult binding,
                           HttpServletResponse response, Model model) {
        if (binding.hasErrors()) return fail(token, model, response, firstError(binding));
        if (!form.password().equals(form.passwordConfirm())) {
            return fail(token, model, response, "비밀번호가 서로 일치하지 않습니다.");
        }
        try {
            signupService.confirmPassword(token, form.password());
        } catch (SignupException e) {
            return fail(token, model, response, e.getMessage());
        }
        return "redirect:/signup";
    }

    @PostMapping("/signup/complete")
    public String complete(@CookieValue(name = COOKIE, required = false) String token,
                           HttpServletResponse response, Model model) {
        Member member;
        try {
            member = signupService.complete(token);
        } catch (SignupException e) {
            return fail(token, model, response, e.getMessage());
        }
        cookies.add(response, cookies.build(COOKIE, "", "/signup", Duration.ZERO));
        authService.startSession(member, response);   // 가입 직후 로그인 상태
        events.record(SecurityEvent.of(SecurityEventType.SIGNUP_COMPLETE, EventOutcome.SUCCESS).memberId(member.getId()));
        return "redirect:/dashboard";
    }

    private String fail(String token, Model model, HttpServletResponse response, String message) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return render(token, model, message);
    }

    private String render(String token, Model model, String error) {
        Optional<SignupRequest> request = signupService.find(token);
        model.addAttribute("step", request.map(SignupRequest::step).map(Enum::name).orElse("EMAIL"));
        model.addAttribute("signup", request.orElse(null));
        model.addAttribute("error", error);
        model.addAttribute("codeMinutes", props.codeTtl().toMinutes());
        return VIEW;
    }

    private static String firstError(BindingResult binding) {
        return binding.getAllErrors().get(0).getDefaultMessage();
    }
}
