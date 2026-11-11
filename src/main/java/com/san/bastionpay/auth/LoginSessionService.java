package com.san.bastionpay.auth;

import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.member.TierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 마지막 활동 후 일정 시간(app.session.idle-timeout)이 지나면 다시 로그인하게 하는 세션 관리.
 * 인증이 필요한 요청이 올 때마다 만료 시각을 뒤로 밀고(touch), 남은 시간이 0 이 되면 세션을 끝낸다.
 * 세션이 끝나면 Access Token 이 아직 유효 기간 안이어도 더는 쓸 수 없다(로그아웃도 즉시 효력이 있다).
 */
@Slf4j
@Service
public class LoginSessionService {

    public enum State { ACTIVE, EXPIRED, GONE }

    /** remainingSeconds 는 ACTIVE 일 때만 의미가 있다. */
    public record Status(State state, long remainingSeconds, Long memberId) {
        static Status active(long remaining, Long memberId) { return new Status(State.ACTIVE, remaining, memberId); }
        static Status expired(Long memberId) { return new Status(State.EXPIRED, 0, memberId); }
        static Status gone() { return new Status(State.GONE, 0, null); }
    }

    private final LoginSessionRepository sessions;
    private final SessionProperties props;
    private final SecurityEventRecorder events;
    private final TierService tiers;

    public LoginSessionService(LoginSessionRepository sessions, SessionProperties props, SecurityEventRecorder events, TierService tiers) {
        this.tiers = tiers;
        this.sessions = sessions;
        this.props = props;
        this.events = events;
    }

    public Duration idleTimeout() {
        return props.idleTimeout();
    }

    @Transactional
    public void start(Long memberId, String sessionId) {
        LocalDateTime now = now();
        sessions.saveAndFlush(new LoginSession(sessionId, memberId, now, now.plus(props.idleTimeout())));
    }

    /**
     * 활동으로 본다. 살아 있으면 만료 시각을 지금 + idle-timeout 으로 민다(touch-interval 보다 자주 쓰지는 않는다).
     * force 는 "시간 연장" 버튼용으로 간격과 상관없이 민다.
     */
    @Transactional
    public Status touch(String sessionId, boolean force) {
        LocalDateTime now = now();
        LoginSession s = sessions.findById(sessionId).orElse(null);
        if (s == null || s.getEndedAt() != null) return Status.gone();
        if (!s.getIdleExpiresAt().isAfter(now)) return Status.expired(s.getMemberId());

        long remaining = Duration.between(now, s.getIdleExpiresAt()).toSeconds();
        long sinceLastTouch = props.idleTimeout().toSeconds() - remaining;
        if (force || sinceLastTouch >= props.touchInterval().toSeconds()) {
            if (sessions.touch(sessionId, now, now.plus(props.idleTimeout())) == 0) return Status.expired(s.getMemberId());
            remaining = props.idleTimeout().toSeconds();
            tiers.recordActivity(s.getMemberId());   // 앱을 이용 중이다(강등 기준의 마지막 이용 시각, 하루에 한 번만 기록)
        }
        return Status.active(remaining, s.getMemberId());
    }

    /** 남은 시간만 본다. 활동으로 치지 않는다(남은 시간을 주기적으로 묻는 요청이 세션을 늘리지 않도록). */
    @Transactional(readOnly = true)
    public Status peek(String sessionId) {
        LoginSession s = sessions.findById(sessionId).orElse(null);
        if (s == null || s.getEndedAt() != null) return Status.gone();
        LocalDateTime now = now();
        if (!s.getIdleExpiresAt().isAfter(now)) return Status.expired(s.getMemberId());
        return Status.active(Duration.between(now, s.getIdleExpiresAt()).toSeconds(), s.getMemberId());
    }

    /** 로그아웃·폐기 등으로 세션을 끝낸다. */
    @Transactional
    public void end(String sessionId, String reason) {
        sessions.end(sessionId, now(), reason);
    }

    /** 활동이 없어 만료된 세션을 끝내고 한 번만 이벤트를 남긴다. */
    @Transactional
    public void expire(String sessionId, Long memberId) {
        if (sessions.end(sessionId, now(), "IDLE_TIMEOUT") == 1) {
            events.record(SecurityEvent.of(SecurityEventType.SESSION_EXPIRED, EventOutcome.DENIED)
                    .memberId(memberId).reasonCode("IDLE_TIMEOUT"));
        }
    }

    /** 끝난 지 오래된 세션 기록 정리. */
    @Scheduled(cron = "0 40 4 * * *")
    @Transactional
    public void purge() {
        int deleted = sessions.deleteOlderThan(now().minusDays(1));
        if (deleted > 0) log.debug("지난 로그인 세션 {}건 삭제", deleted);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
