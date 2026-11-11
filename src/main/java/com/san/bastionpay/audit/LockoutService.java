package com.san.bastionpay.audit;

import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 로그인 실패 자동 잠금. 합성 테스트 계정(LockoutProperties.allowlist)에만 적용하고, 관리자 계정은 대상이 아니다.
 * 잠금과 해제는 보안 이벤트(ACCOUNT_LOCKED/UNLOCKED)와 감사 기록(audit_log)에 같은 event_id 로 남긴다.
 */
@Service
public class LockoutService {

    /** 로그인 시도 전 확인 결과. */
    public enum Gate { OPEN, LOCKED }

    private final AccountLockoutRepository lockouts;
    private final AuditLogRepository audits;
    private final SecurityEventRecorder events;
    private final LockoutProperties props;

    public LockoutService(AccountLockoutRepository lockouts, AuditLogRepository audits, SecurityEventRecorder events,
                          LockoutProperties props) {
        this.lockouts = lockouts;
        this.audits = audits;
        this.events = events;
        this.props = props;
    }

    /** 이 계정이 자동 잠금 대상인가. */
    public boolean applies(Member member) {
        return member.getRole() != MemberRole.ADMIN && props.covers(member.getEmail());
    }

    /** 로그인 시도 전에 잠겨 있는지 본다. 잠금 시간이 지났으면 이때 해제한다. */
    @Transactional
    public Gate check(Member member, String subjectHash) {
        if (!applies(member)) return Gate.OPEN;
        LocalDateTime now = now();
        AccountLockout lockout = lockouts.findForUpdate(member.getId()).orElse(null);
        if (lockout == null) return Gate.OPEN;
        if (lockout.isLocked(now)) return Gate.LOCKED;
        if (lockout.isLockExpired(now)) {
            lockout.reset(now);
            recordUnlock(member.getId(), subjectHash, "LOCK_EXPIRED", AuditLog.ActorType.SYSTEM, null);
        }
        return Gate.OPEN;
    }

    /** 비밀번호가 틀렸을 때 호출한다. 이번 실패로 잠금이 걸리면 true. */
    @Transactional
    public boolean onFailure(Member member, String subjectHash) {
        if (!applies(member)) return false;
        LocalDateTime now = now();
        AccountLockout lockout = load(member.getId(), now);
        if (lockout.isLocked(now)) return true;   // 잠겨 있는 동안의 시도는 세지 않는다(잠금이 계속 늘어나지 않게)
        if (!lockout.recordFailure(now, props.window(), props.maxFailures())) return false;
        lockout.lock(now, props.lockDuration());
        SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.ACCOUNT_LOCKED, EventOutcome.DENIED)
                .memberId(member.getId()).loginSubjectHash(subjectHash).reasonCode("FAILED_LOGIN_THRESHOLD"));
        audits.save(new AuditLog(event, AuditLog.ActorType.SYSTEM, null, member.getId()));
        return true;
    }

    /** 로그인에 성공하면 실패 누적을 지운다. */
    @Transactional
    public void onSuccess(Member member) {
        if (!applies(member)) return;
        lockouts.findForUpdate(member.getId()).ifPresent(l -> {
            if (l.getFailedCount() > 0) l.reset(now());
        });
    }

    /** 관리자가 자동 잠금을 푼다. 잠겨 있지 않았으면 false. */
    @Transactional
    public boolean unlock(long targetMemberId, long adminMemberId) {
        return unlock(targetMemberId, Actor.admin(adminMemberId, "admin"));
    }

    /** 자동 잠금(로그인 실패 누적)을 푼다. 누가 풀었는지는 감사 기록에 남는다. 잠겨 있지 않았으면 false. */
    @Transactional
    public boolean unlock(long targetMemberId, Actor actor) {
        LocalDateTime now = now();
        Optional<AccountLockout> found = lockouts.findForUpdate(targetMemberId);
        if (found.isEmpty() || !found.get().isLocked(now)) return false;
        found.get().reset(now);
        recordUnlock(targetMemberId, null, actor.type() == AuditLog.ActorType.API ? "API_UNLOCK" : "ADMIN_UNLOCK", actor.type(), actor.memberId());
        return true;
    }

    private void recordUnlock(long memberId, String subjectHash, String reason, AuditLog.ActorType actor, Long actorId) {
        SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.ACCOUNT_UNLOCKED, EventOutcome.SUCCESS)
                .memberId(memberId).loginSubjectHash(subjectHash).reasonCode(reason));
        audits.save(new AuditLog(event, actor, actorId, memberId));
    }

    private AccountLockout load(long memberId, LocalDateTime now) {
        lockouts.insertIfAbsent(memberId, now);
        return lockouts.findForUpdate(memberId).orElseThrow();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
