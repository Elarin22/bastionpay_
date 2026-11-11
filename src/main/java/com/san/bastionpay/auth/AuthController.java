package com.san.bastionpay.auth;

import com.san.bastionpay.audit.LoginSubjectHasher;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.member.Member;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Slf4j
@Controller
public class AuthController {

    private static final String LOGIN_FAILED = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private final AuthService authService;
    private final SecurityEventRecorder events;
    private final LoginSubjectHasher subjects;

    public AuthController(AuthService authService, SecurityEventRecorder events, LoginSubjectHasher subjects) {
        this.authService = authService;
        this.events = events;
        this.subjects = subjects;
    }

    @GetMapping("/login")
    public String loginPage(Authentication authentication, Model model,
                            @RequestParam(required = false) String required,
                            @RequestParam(required = false) String expired,
                            @RequestParam(required = false) String registered) {
        if (isLoggedIn(authentication)) return "redirect:/dashboard";
        // 보호된 화면에서 EntryPoint 가 보낸 경우. 원래 경로는 받지 않는다(오픈 리다이렉트 방지)
        if (expired != null) model.addAttribute("notice", "일정 시간 활동이 없어 로그아웃되었습니다. 다시 로그인해 주세요.");
        else if (registered != null) model.addAttribute("notice", "이미 가입된 이메일입니다. 로그인해 주세요.");
        else if (required != null) model.addAttribute("notice", "로그인이 필요한 화면입니다. 로그인한 뒤 이용해 주세요.");
        model.addAttribute("loginForm", new LoginForm("", ""));
        return "auth/login";
    }

    @PostMapping("/login")
    public String login(@Valid @ModelAttribute("loginForm") LoginForm form, BindingResult binding,
                        HttpServletResponse response, Model model) {
        AuthService.LoginResult result = binding.hasErrors() ? null : authService.login(form.email(), form.password());
        Optional<Member> member = result == null ? Optional.empty() : result.member();
        if (member.isEmpty()) {
            // 계정은 이메일 원문이 아니라 서버 키로 만든 식별값으로 남긴다(같은 계정·같은 IP 의 실패 횟수를 셀 수 있다)
            boolean denied = result != null && result.denied();   // 잠긴 계정·비활성 계정은 실패가 아니라 거부로 기록한다
            events.record(SecurityEvent.of(SecurityEventType.LOGIN_FAILURE, denied ? EventOutcome.DENIED : EventOutcome.FAILURE)
                    .loginSubjectHash(subjects.hash(form.email()))
                    .reasonCode(result == null ? "INVALID_INPUT" : result.reasonCode()));
            model.addAttribute("error", binding.hasErrors() ? "이메일과 비밀번호를 입력해 주세요." : LOGIN_FAILED);
            model.addAttribute("loginForm", new LoginForm(form.email(), ""));   // 비밀번호는 되돌려 주지 않는다
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return "auth/login";
        }
        authService.startSession(member.get(), response);
        events.record(SecurityEvent.of(SecurityEventType.LOGIN_SUCCESS, EventOutcome.SUCCESS)
                .memberId(member.get().getId()).loginSubjectHash(subjects.hash(form.email())));
        return "redirect:/dashboard";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        authService.endSession(request, response);
        return "redirect:/";
    }

    private static boolean isLoggedIn(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AuthMember;
    }
}
