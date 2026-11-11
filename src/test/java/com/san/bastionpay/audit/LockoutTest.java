package com.san.bastionpay.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.san.bastionpay.auth.AuthService;
import com.san.bastionpay.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 실패 자동 잠금. 합성 테스트 계정(allowlist)만 잠기고, 잠금·해제가 보안 이벤트와 감사 기록에 남는지 확인한다.
 * 로컬 MySQL 과 db/seed/member.sql 의 더미 회원이 필요하다. 테스트 안에서 바꾼 DB 는 모두 롤백된다.
 */
@SpringBootTest
@Transactional
class LockoutTest {

    private static final String GOOD = "12345678A!";

    @Autowired AuthService auth;
    @Autowired LockoutService lockoutService;
    @Autowired AccountLockoutRepository lockouts;
    @Autowired AuditLogRepository audits;
    @Autowired MemberRepository members;
    @Autowired LockoutProperties props;

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private Logger eventLogger;

    @BeforeEach
    void attach() {
        eventLogger = (Logger) LoggerFactory.getLogger(SecurityEventRecorder.LOGGER_NAME);
        lines.start();
        eventLogger.addAppender(lines);
    }

    @AfterEach
    void detach() {
        eventLogger.detachAppender(lines);
    }

    private List<String> eventTypes() {
        return lines.list.stream().map(ILoggingEvent::getFormattedMessage)
                .map(m -> m.replaceAll(".*\"event_type\":\"([A-Z_]+)\".*", "$1")).toList();
    }

    private void failTimes(String email, int n) {
        for (int i = 0; i < n; i++) assertThat(auth.login(email, "wrong-password-" + i).member()).isEmpty();
    }

    @Test
    void synthesizedTestAccountIsLockedAfterTheConfiguredFailuresEvenWithTheRightPassword() {
        assertThat(props.allowlist()).contains("test@test.com");
        failTimes("test@test.com", props.maxFailures() - 1);
        assertThat(auth.login("test@test.com", GOOD).member()).as("한도 전에는 올바른 비밀번호로 로그인된다").isPresent();   // 성공하면 누적이 지워진다

        failTimes("test@test.com", props.maxFailures());
        AuthService.LoginResult locked = auth.login("test@test.com", GOOD);

        assertThat(locked.member()).isEmpty();
        assertThat(locked.reasonCode()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(locked.denied()).isTrue();
        assertThat(eventTypes()).contains("ACCOUNT_LOCKED");
        long memberId = members.findByEmail("test@test.com").orElseThrow().getId();
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(SecurityEventType.ACCOUNT_LOCKED);
            assertThat(a.getActorType()).isEqualTo(AuditLog.ActorType.SYSTEM);
            assertThat(a.getTargetMemberId()).isEqualTo(memberId);
            assertThat(a.getReasonCode()).isEqualTo("FAILED_LOGIN_THRESHOLD");
            assertThat(a.getEventId()).isNotBlank();
        });
    }

    @Test
    void accountsOutsideTheAllowlistAreNeverLocked() {
        assertThat(props.allowlist()).doesNotContain("admin@test.com");
        failTimes("admin@test.com", props.maxFailures() + 5);

        assertThat(auth.login("admin@test.com", GOOD).member()).isPresent();
        assertThat(eventTypes()).doesNotContain("ACCOUNT_LOCKED");
        assertThat(lockouts.findAll()).noneMatch(l -> l.getMemberId().equals(members.findByEmail("admin@test.com").orElseThrow().getId()));
    }

    @Test
    void lockExpiresByItselfAndIsRecordedAsAnUnlock() {
        failTimes("vip@test.com", props.maxFailures());
        assertThat(auth.login("vip@test.com", GOOD).reasonCode()).isEqualTo("ACCOUNT_LOCKED");
        long id = members.findByEmail("vip@test.com").orElseThrow().getId();
        AccountLockout row = lockouts.findById(id).orElseThrow();
        // 잠금 시간이 지난 것처럼 되돌린다
        lockouts.saveAndFlush(expired(row));

        assertThat(auth.login("vip@test.com", GOOD).member()).isPresent();
        assertThat(eventTypes()).contains("ACCOUNT_UNLOCKED");
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(SecurityEventType.ACCOUNT_UNLOCKED);
            assertThat(a.getReasonCode()).isEqualTo("LOCK_EXPIRED");
        });
    }

    @Test
    void adminUnlockClearsTheLockAndRecordsWhoDidIt() {
        failTimes("vvip@test.com", props.maxFailures());
        long target = members.findByEmail("vvip@test.com").orElseThrow().getId();
        long admin = members.findByEmail("admin@test.com").orElseThrow().getId();

        assertThat(lockoutService.unlock(target, admin)).isTrue();
        assertThat(lockoutService.unlock(target, admin)).as("이미 풀린 계정").isFalse();

        assertThat(auth.login("vvip@test.com", GOOD).member()).isPresent();
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(SecurityEventType.ACCOUNT_UNLOCKED);
            assertThat(a.getActorType()).isEqualTo(AuditLog.ActorType.ADMIN);
            assertThat(a.getActorMemberId()).isEqualTo(admin);
            assertThat(a.getReasonCode()).isEqualTo("ADMIN_UNLOCK");
        });
    }

    @Test
    void failuresOutsideTheWindowStartCountingAgain() {
        LocalDateTime t0 = LocalDateTime.now(ZoneOffset.UTC);
        AccountLockout l = new AccountLockout(1L, t0);
        Duration window = Duration.ofMinutes(10);

        for (int i = 0; i < 4; i++) assertThat(l.recordFailure(t0.plusMinutes(i), window, 5)).isFalse();
        // 시간창(10분)이 지난 뒤의 실패는 처음부터 센다
        assertThat(l.recordFailure(t0.plusMinutes(30), window, 5)).isFalse();
        assertThat(l.getFailedCount()).isEqualTo(1);
    }

    private static AccountLockout expired(AccountLockout row) {
        row.reset(LocalDateTime.now(ZoneOffset.UTC));
        row.lock(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30), Duration.ofMinutes(15));   // 15분 잠금이 15분 전에 끝남
        return row;
    }
}
