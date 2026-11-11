package com.san.bastionpay.auth;

import com.san.bastionpay.audit.LockoutService;
import com.san.bastionpay.audit.LoginSubjectHasher;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.TierService;
import com.san.bastionpay.member.MemberRepository;
import com.san.bastionpay.member.MemberStatus;
import com.san.bastionpay.signup.SignupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;
    /** 없는 이메일도 해시 비교 시간을 쓰게 해 응답 시간으로 계정 존재 여부를 알 수 없게 한다. */
    private final String dummyHash;

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final AuthCookies cookies;
    private final LockoutService lockouts;
    private final LoginSubjectHasher subjects;
    private final LoginSessionService sessions;
    private final TierService tiers;

    public AuthService(MemberRepository members, PasswordEncoder passwordEncoder, JwtService jwtService,
                       RefreshTokenService refreshTokens, AuthCookies cookies, LockoutService lockouts,
                       LoginSubjectHasher subjects, LoginSessionService sessions, TierService tiers) {
        this.sessions = sessions;
        this.tiers = tiers;
        this.lockouts = lockouts;
        this.subjects = subjects;
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /** 로그인 결과. 실패 사유(reasonCode)는 보안 이벤트에만 쓰고, 화면에는 항상 같은 문구를 보여 준다. */
    public record LoginResult(Optional<Member> member, String reasonCode, boolean denied) {
        static LoginResult success(Member m) { return new LoginResult(Optional.of(m), null, false); }
        static LoginResult failure(String reason) { return new LoginResult(Optional.empty(), reason, false); }
        static LoginResult denied(String reason) { return new LoginResult(Optional.empty(), reason, true); }
    }

    /**
     * 이메일·비밀번호 확인. 없는 계정도 해시 비교 시간을 똑같이 쓰고, 실패 사유는 호출자에게만 돌려준다.
     * 자동 잠금 대상 계정(합성 테스트 계정)은 비밀번호가 맞아도 잠겨 있는 동안에는 거부한다.
     */
    public LoginResult login(String email, String password) {
        String normalized = SignupService.normalize(email);
        Optional<Member> found = members.findByEmail(normalized);
        boolean matches = passwordEncoder.matches(password, found.map(Member::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty()) return LoginResult.failure("BAD_CREDENTIALS");
        Member member = found.get();
        if (member.getStatus() != MemberStatus.ACTIVE) return LoginResult.denied(member.getStatus() == MemberStatus.LOCKED ? "ACCOUNT_LOCKED" : "ACCOUNT_INACTIVE");

        String subject = subjects.hash(normalized);
        if (lockouts.check(member, subject) == LockoutService.Gate.LOCKED) return LoginResult.denied("ACCOUNT_LOCKED");
        if (!matches) {
            lockouts.onFailure(member, subject);
            return LoginResult.failure("BAD_CREDENTIALS");
        }
        lockouts.onSuccess(member);
        return LoginResult.success(member);
    }

    /** 로그인·가입 직후: Access Token 과 새 family 의 Refresh Token 을 쿠키로 내려준다. */
    public void startSession(Member member, HttpServletResponse response) {
        tiers.recordLogin(member);   // 오래 이용하지 않았다면 먼저 등급을 내리고, 마지막 이용 시각을 지금으로 갱신한다
        RefreshTokenService.Issued issued = refreshTokens.issue(member);
        sessions.start(member.getId(), issued.familyId());   // 이 로그인의 세션. 마지막 활동 후 30분이 지나면 끝난다
        cookies.writeAccessToken(response, jwtService.issueAccessToken(member, issued.familyId()));
        cookies.writeRefreshToken(response, issued);
    }

    /** 로그아웃: 이 브라우저의 Refresh Token 계열을 폐기하고 쿠키를 지운다. */
    public void endSession(HttpServletRequest request, HttpServletResponse response) {
        String refresh = AuthCookies.read(request, AuthCookies.REFRESH_TOKEN);
        if (refresh != null) {
            refreshTokens.familyIdOf(refresh).ifPresent(family -> sessions.end(family, "LOGOUT"));
            refreshTokens.revokeFamilyOf(refresh);
        }
        // Access Token 의 세션도 끝낸다(Refresh Token 이 없어도 이 브라우저의 토큰은 바로 쓸 수 없게 된다)
        String access = AuthCookies.read(request, AuthCookies.ACCESS_TOKEN);
        if (access != null) jwtService.parseSession(access).map(JwtService.Parsed::sessionId).ifPresent(sid -> sessions.end(sid, "LOGOUT"));
        cookies.clearAccessToken(response);
        cookies.clearRefreshToken(response);
    }
}
