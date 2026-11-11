package com.san.mockbank.phone;

import com.san.mockbank.core.Tokens;
import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.customer.BankCustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 휴대폰 문자 인증.
 * <ul>
 *   <li>인증번호는 6자리 난수이고 원문은 저장하지 않는다(salt + SHA-256). 비교는 시간이 일정하게 한다.</li>
 *   <li>유효 시간, 틀릴 수 있는 횟수, 재발송 간격, 번호당 시간당 발송 횟수에 제한이 있다.</li>
 *   <li>등록되지 않은 번호에도 똑같이 "보냈다"고 응답하고(문자만 보내지 않는다) 번호의 등록 여부를 알려 주지 않는다.</li>
 *   <li>새로 발급하면 이전 인증번호는 쓸 수 없다.</li>
 * </ul>
 */
@Slf4j
@Service
public class PhoneAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsVerificationRepository verifications;
    private final BankCustomerRepository customers;
    private final SmsSender sender;
    private final PhoneAuthProperties props;
    private final SmsProperties smsProps;

    public PhoneAuthService(SmsVerificationRepository verifications, BankCustomerRepository customers, SmsSender sender,
                            PhoneAuthProperties props, SmsProperties smsProps) {
        this.verifications = verifications;
        this.customers = customers;
        this.sender = sender;
        this.props = props;
        this.smsProps = smsProps;
    }

    /**
     * 인증번호를 만들어 문자로 보낸다. 번호 형식·재발송 간격·시간당 횟수를 넘으면 PhoneAuthException.
     * 개발용 콘솔 발송 중이고 등록된 번호일 때만 인증번호(devCode)를 돌려준다(화면에 보여 주는 용도).
     */
    @Transactional(noRollbackFor = PhoneAuthException.class)
    public Started start(String phoneInput) {
        String phone = PhoneNumbers.normalize(phoneInput);
        if (phone == null) throw new PhoneAuthException("휴대폰 번호를 확인해 주세요. 예: 010-1234-5678", false);
        LocalDateTime now = LocalDateTime.now();

        var last = verifications.findFirstByPhoneOrderByIdDesc(phone);
        if (last.isPresent() && last.get().getCreatedAt().plus(props.resendInterval()).isAfter(now)) {
            long wait = Math.max(1, Duration.between(now, last.get().getCreatedAt().plus(props.resendInterval())).toSeconds() + 1);
            throw new PhoneAuthException(wait + "초 뒤에 다시 요청해 주세요.", false);
        }
        if (verifications.countByPhoneAndCreatedAtAfter(phone, now.minusHours(1)) >= props.maxSendsPerHour()) {
            log.warn("security_event type=SMS_RATE_LIMITED phone={}", PhoneNumbers.mask(phone));
            throw new PhoneAuthException("인증번호 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", false);
        }

        // 이전에 보낸 인증번호는 더 이상 쓸 수 없다
        verifications.findByPhoneAndConsumedAtIsNull(phone).forEach(SmsVerification::consume);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String salt = HexFormat.of().formatHex(saltBytes());
        SmsVerification saved = verifications.save(new SmsVerification(phone, salt, hash(salt, code), now.plus(props.codeTtl())));

        boolean registered = customers.findByPhone(phone).filter(BankCustomer::isActive).isPresent();
        if (registered) {
            try {
                sender.send(phone, "[BastionPay 테스트은행] 인증번호 " + code + " 를 " + props.codeTtl().toMinutes() + "분 안에 입력해 주세요.");
            } catch (SmsSendException e) {
                // 보내지 못한 인증번호는 남기지 않는다(재발송 간격·시간당 횟수에 계산되지 않도록)
                verifications.delete(saved);
                throw new PhoneAuthException("문자를 보내지 못했습니다. 잠시 후 다시 시도해 주세요.", false);
            }
        } else {
            log.info("security_event type=SMS_UNREGISTERED_PHONE phone={}", PhoneNumbers.mask(phone));
        }
        boolean reveal = registered && smsProps.showCodeOnPage() && sender.revealsCode();
        return new Started(saved.getId(), phone, reveal ? code : null, saved.getExpiresAt());
    }

    /**
     * 인증번호를 확인한다. 성공하면 그 번호로 등록된 활성 고객을 돌려준다.
     * 틀린 시도도 횟수에 기록한다(그래서 롤백하지 않는다). 횟수를 넘거나 만료되면 처음부터 다시 해야 한다.
     */
    @Transactional(noRollbackFor = PhoneAuthException.class)
    public BankCustomer verify(Long verificationId, String phone, String code) {
        SmsVerification v = verificationId == null ? null : verifications.findById(verificationId).orElse(null);
        if (v == null || !v.getPhone().equals(phone) || v.isConsumed()) {
            throw new PhoneAuthException("인증 요청이 만료되었습니다. 처음부터 다시 시도해 주세요.", true);
        }
        if (v.isExpired(LocalDateTime.now())) {
            v.consume();
            throw new PhoneAuthException("인증번호 유효 시간이 지났습니다. 인증번호를 다시 받아 주세요.", false);
        }
        if (v.getAttempts() >= props.maxAttempts()) {
            v.consume();
            throw new PhoneAuthException("인증번호를 여러 번 잘못 입력했습니다. 처음부터 다시 시도해 주세요.", true);
        }
        v.recordAttempt();
        String input = code == null ? "" : code.trim();
        boolean match = input.matches("^\\d{6}$")
                && MessageDigest.isEqual(hash(v.getCodeSalt(), input).getBytes(StandardCharsets.UTF_8), v.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!match) {
            log.warn("security_event type=SMS_CODE_MISMATCH phone={} attempts={}", PhoneNumbers.mask(phone), v.getAttempts());
            if (v.getAttempts() >= props.maxAttempts()) {
                v.consume();
                throw new PhoneAuthException("인증번호를 여러 번 잘못 입력했습니다. 처음부터 다시 시도해 주세요.", true);
            }
            throw new PhoneAuthException("인증번호가 맞지 않습니다. (" + (props.maxAttempts() - v.getAttempts()) + "회 남음)", false);
        }
        v.consume();
        // 인증번호가 맞아도, 그 번호로 등록된 활성 고객이 없으면 실패로 응답한다(번호의 등록 여부는 알려 주지 않는다)
        Optional<BankCustomer> customer = customers.findByPhone(phone).filter(BankCustomer::isActive);
        if (customer.isEmpty()) throw new PhoneAuthException("인증에 실패했습니다. 처음부터 다시 시도해 주세요.", true);
        log.info("security_event type=SMS_VERIFIED phone={}", PhoneNumbers.mask(phone));
        return customer.get();
    }

    /** 오래된 인증 기록 정리. */
    @Scheduled(cron = "0 30 4 * * *")
    public void purge() {
        int removed = verifications.deleteExpiredBefore(LocalDateTime.now().minusDays(1));
        if (removed > 0) log.debug("만료된 인증번호 {}건 삭제", removed);
    }

    private static byte[] saltBytes() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String hash(String salt, String code) {
        return Tokens.sha256(salt + ":" + code);
    }

    /** devCode 는 개발용 화면 표시에만 쓰며 로그에 남기지 않는다. */
    public record Started(Long verificationId, String phone, String devCode, LocalDateTime expiresAt) {
        @Override
        public String toString() {
            return "Started[verificationId=" + verificationId + "]";
        }
    }
}
