package com.san.bastionpay.signup;

import com.san.bastionpay.core.mail.MailSendException;
import com.san.bastionpay.core.mail.MailService;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 회원가입 단계 처리. 잠금 규칙:
 * <ul>
 *   <li>이메일 인증이 끝나면 이메일은 바꿀 수 없다 (처음부터 다시 시작도 막는다).</li>
 *   <li>비밀번호 확인이 끝나면 비밀번호는 바꿀 수 없다.</li>
 * </ul>
 */
@Slf4j
@Service
public class SignupService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SignupRequestRepository requests;
    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final SignupProperties props;

    public SignupService(SignupRequestRepository requests, MemberRepository members,
                         PasswordEncoder passwordEncoder, MailService mailService, SignupProperties props) {
        this.requests = requests;
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.props = props;
    }

    /** 진행 중인 가입 요청을 찾는다. 만료됐으면 없는 것으로 본다. */
    @Transactional(readOnly = true)
    public Optional<SignupRequest> find(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return requests.findByTokenHash(sha256(token)).filter(r -> !r.isExpired());
    }

    /**
     * 1단계: 이메일로 인증 코드를 보낸다. 새 토큰을 돌려주며 컨트롤러가 쿠키로 내려준다.
     * 메일 전송은 시간이 걸려 DB 트랜잭션 밖에서 한다.
     */
    public String start(String currentToken, String rawEmail) {
        find(currentToken).ifPresent(existing -> {
            if (existing.isEmailVerified()) {
                throw new SignupException("이메일 인증이 끝나 이메일을 변경할 수 없습니다.");
            }
            requests.delete(existing);
        });

        String email = normalize(rawEmail);
        // 이미 가입된 이메일, 이미 인증을 마치고 가입을 진행 중인 이메일은 다시 인증할 수 없다. 메일도 보내지 않는다
        rejectIfTaken(email);
        String token = randomToken();
        String code = randomCode();
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = sha256(token);
        SignupRequest request = new SignupRequest(tokenHash, email, codeHash(tokenHash, code),
                now.plus(props.codeTtl()), now.plus(props.requestTtl()));
        requests.save(request);

        try {
            mailService.sendVerificationCode(email, code, props.codeTtl());
        } catch (MailSendException e) {
            requests.delete(request);
            log.error("인증 메일 발송 실패: {}", e.getMessage());
            throw new SignupException("인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return token;
    }

    /** 인증 코드를 다시 보낸다. 이메일이 인증된 뒤에는 불가. */
    public void resend(String token) {
        SignupRequest request = require(token);
        if (request.isEmailVerified()) {
            throw new SignupException("이미 인증이 끝났습니다.");
        }
        Duration wait = props.resendInterval().minus(Duration.between(request.getLastSentAt(), LocalDateTime.now()));
        if (!wait.isNegative() && !wait.isZero()) {
            throw new SignupException(wait.toSeconds() + 1 + "초 뒤에 다시 요청할 수 있습니다.");
        }
        rejectIfTaken(request.getEmail());   // 그 사이 같은 이메일로 가입했거나 인증을 마친 사람이 생겼을 수 있다
        String code = randomCode();
        saveRenewedCode(request, codeHash(request.getTokenHash(), code));
        try {
            mailService.sendVerificationCode(request.getEmail(), code, props.codeTtl());
        } catch (MailSendException e) {
            log.error("인증 메일 재발송 실패: {}", e.getMessage());
            throw new SignupException("인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private void rejectIfTaken(String email) {
        if (members.existsByEmail(email)) {
            throw new AlreadyRegisteredException();
        }
        if (requests.existsByEmailAndEmailVerifiedAtIsNotNullAndExpiresAtAfter(email, LocalDateTime.now())) {
            throw new SignupException("이미 이 이메일로 인증을 마치고 가입을 진행 중입니다. 진행 중인 가입을 마치거나 잠시 후 다시 시도해 주세요.");
        }
    }

    private void saveRenewedCode(SignupRequest request, String codeHash) {
        request.renewCode(codeHash, LocalDateTime.now().plus(props.codeTtl()));
        requests.save(request);
    }

    /** 이메일을 잘못 입력했을 때 처음부터 다시. 인증 전에만 가능. */
    @Transactional
    public void restart(String token) {
        find(token).ifPresent(request -> {
            if (request.isEmailVerified()) {
                throw new SignupException("이메일 인증이 끝나 이메일을 변경할 수 없습니다.");
            }
            requests.delete(request);
        });
    }

    @Transactional(noRollbackFor = SignupException.class)
    public void verifyCode(String token, String code) {
        SignupRequest request = require(token);
        if (request.isEmailVerified()) {
            return;
        }
        requests.increaseAttempts(request.getId());
        request = requests.findById(request.getId()).orElseThrow(() -> new SignupException(EXPIRED));

        if (request.getCodeAttempts() > props.codeMaxAttempts()) {
            throw new SignupException("시도 횟수를 초과했습니다. 인증 코드를 다시 받아 주세요.");
        }
        if (request.getCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new SignupException("인증 코드가 만료되었습니다. 인증 코드를 다시 받아 주세요.");
        }
        byte[] expected = request.getCodeHash().getBytes(StandardCharsets.UTF_8);
        byte[] actual = codeHash(request.getTokenHash(), code).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            int left = props.codeMaxAttempts() - request.getCodeAttempts();
            throw new SignupException("인증 코드가 올바르지 않습니다. (남은 시도 " + Math.max(left, 0) + "회)");
        }
        // 같은 이메일을 다른 브라우저에서 먼저 인증한 사람이 있으면 두 번째 인증은 받아 주지 않는다
        if (requests.existsByEmailAndEmailVerifiedAtIsNotNullAndExpiresAtAfterAndIdNot(request.getEmail(), LocalDateTime.now(), request.getId())) {
            throw new SignupException("이미 이 이메일로 인증을 마치고 가입을 진행 중입니다. 진행 중인 가입을 마치거나 잠시 후 다시 시도해 주세요.");
        }
        request.markEmailVerified();
        requests.save(request);
    }

    @Transactional
    public void saveProfile(String token, String name, String nickname) {
        SignupRequest request = require(token);
        if (!request.isEmailVerified()) {
            throw new SignupException("이메일 인증을 먼저 완료해 주세요.");
        }
        if (request.isPasswordConfirmed()) {
            throw new SignupException("비밀번호 확인이 끝난 뒤에는 이름과 닉네임을 바꿀 수 없습니다.");
        }
        if (members.existsByNickname(nickname)) {
            throw new SignupException("이미 사용 중인 닉네임입니다.");
        }
        request.changeProfile(name, nickname);
        requests.save(request);
    }

    @Transactional
    public void confirmPassword(String token, String password) {
        SignupRequest request = require(token);
        if (!request.hasProfile()) {
            throw new SignupException("이름과 닉네임을 먼저 입력해 주세요.");
        }
        if (request.isPasswordConfirmed()) {
            throw new SignupException("비밀번호는 이미 확인되어 변경할 수 없습니다.");
        }
        request.confirmPassword(passwordEncoder.encode(password));
        requests.save(request);
    }

    @Transactional
    public Member complete(String token) {
        SignupRequest request = require(token);
        if (request.step() != SignupStep.REVIEW) {
            throw new SignupException("가입 절차를 모두 마친 뒤에 완료할 수 있습니다.");
        }
        try {
            Member member = members.saveAndFlush(new Member(request.getEmail(), request.getName(),
                    request.getNickname(), request.getPasswordHash()));
            requests.delete(request);
            return member;
        } catch (DataIntegrityViolationException e) {
            // 이메일·닉네임 유니크 충돌 (동시 가입 등)
            throw new SignupException("이미 사용 중인 이메일 또는 닉네임입니다.");
        }
    }

    @Scheduled(cron = "0 10 * * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = requests.deleteExpired(LocalDateTime.now());
        if (deleted > 0) log.debug("만료된 가입 요청 {}건 삭제", deleted);
    }

    private static final String EXPIRED = "가입 진행 시간이 지났습니다. 처음부터 다시 시작해 주세요.";

    private SignupRequest require(String token) {
        return find(token).orElseThrow(() -> new SignupException(EXPIRED));
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** 코드는 토큰 해시를 섞어 저장해 DB가 노출돼도 코드를 그대로 대조할 수 없게 한다. */
    private static String codeHash(String tokenHash, String code) {
        return sha256(tokenHash + ":" + code);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
