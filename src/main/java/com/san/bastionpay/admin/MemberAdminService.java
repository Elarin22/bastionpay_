package com.san.bastionpay.admin;

import com.san.bastionpay.audit.Actor;
import com.san.bastionpay.audit.AuditLog;
import com.san.bastionpay.audit.AuditLogRepository;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.LockoutService;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.auth.LoginSessionRepository;
import com.san.bastionpay.auth.RefreshTokenRepository;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberAdminQuery;
import com.san.bastionpay.member.MemberRepository;
import com.san.bastionpay.member.MemberRole;
import com.san.bastionpay.member.MemberStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 회원 계정 보안 조치: 잠금·해제, 관리자 권한 회수. 관리자(화면)와 자동화(API)가 같은 규칙으로 쓴다.
 * 잠그거나 권한을 회수하면 그 회원의 기존 로그인 세션과 토큰을 모두 끊어서, 이미 받은 토큰으로 계속 쓰지 못하게 한다.
 */
@Service
public class MemberAdminService {

    public enum LockResult { LOCKED, ALREADY_LOCKED, NOT_FOUND, SELF, NOT_ALLOWED, NOT_ACTIVE }

    public enum UnlockResult { UNLOCKED, NOT_LOCKED, NOT_FOUND }

    public enum RevokeResult { REVOKED, NOT_ADMIN, NOT_FOUND, SELF, LAST_ADMIN }

    private final MemberRepository members;
    private final LockoutService lockout;
    private final LoginSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final SecurityEventRecorder events;
    private final AuditLogRepository audits;
    private final AdminSecurityProperties props;
    private final com.san.bastionpay.audit.AccountLockoutRepository lockoutRepository;
    private final java.util.function.Supplier<List<Long>> lockoutIds;

    public MemberAdminService(MemberRepository members, LockoutService lockout, LoginSessionRepository sessions,
                              RefreshTokenRepository refreshTokens, SecurityEventRecorder events, AuditLogRepository audits,
                              AdminSecurityProperties props, com.san.bastionpay.audit.AccountLockoutRepository lockoutRepository) {
        this.lockoutRepository = lockoutRepository;
        this.lockoutIds = () -> lockoutRepository.findLocked(now()).stream().map(com.san.bastionpay.audit.AccountLockout::getMemberId).toList();
        this.members = members;
        this.lockout = lockout;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.events = events;
        this.audits = audits;
        this.props = props;
    }

    /**
     * 계정을 잠근다. 합성 테스트 계정(자동 잠금 allowlist)만 잠글 수 있고, 관리자 계정과 자기 자신은 잠글 수 없다.
     * 잠그는 순간 그 계정의 로그인 세션과 토큰을 모두 끊는다.
     */
    @Transactional
    public LockResult lock(long memberId, String reason, Actor actor) {
        Member member = members.findById(memberId).orElse(null);
        if (member == null) return LockResult.NOT_FOUND;
        if (actor.memberId() != null && actor.memberId() == memberId) return LockResult.SELF;
        if (!lockout.applies(member)) return LockResult.NOT_ALLOWED;
        if (member.getStatus() == MemberStatus.LOCKED) return LockResult.ALREADY_LOCKED;
        if (member.getStatus() != MemberStatus.ACTIVE) return LockResult.NOT_ACTIVE;

        LocalDateTime now = now();
        String why = clean(reason, "잠금 by " + actor.label());
        member.lock(why, now);
        members.save(member);
        cutOffSessions(memberId, now);
        SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.ACCOUNT_LOCKED, EventOutcome.DENIED)
                .memberId(memberId).reasonCode(actor.type() == AuditLog.ActorType.API ? "API_LOCK" : "ADMIN_LOCK"));
        audits.save(new AuditLog(event, actor.type(), actor.memberId(), memberId));
        return LockResult.LOCKED;
    }

    /** 잠금을 푼다. 수동 잠금(status)과 로그인 실패로 걸린 자동 잠금 모두 푼다. */
    @Transactional
    public UnlockResult unlock(long memberId, Actor actor) {
        Member member = members.findById(memberId).orElse(null);
        if (member == null) return UnlockResult.NOT_FOUND;
        boolean unlocked = false;
        if (member.getStatus() == MemberStatus.LOCKED) {
            member.unlock();
            members.save(member);
            SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.ACCOUNT_UNLOCKED, EventOutcome.SUCCESS)
                    .memberId(memberId).reasonCode(actor.type() == AuditLog.ActorType.API ? "API_UNLOCK" : "ADMIN_UNLOCK"));
            audits.save(new AuditLog(event, actor.type(), actor.memberId(), memberId));
            unlocked = true;
        }
        if (lockout.unlock(memberId, actor)) unlocked = true;
        return unlocked ? UnlockResult.UNLOCKED : UnlockResult.NOT_LOCKED;
    }

    /**
     * 관리자 권한을 회수한다(ADMIN → USER). 자기 자신과 마지막 관리자는 회수할 수 없다(관리 불능 방지).
     * 회수하면 그 회원의 세션을 끊어, 이미 받은 관리자 토큰으로 계속 쓰지 못하게 한다.
     */
    @Transactional
    public RevokeResult revokeAdmin(long memberId, String reason, Actor actor) {
        Member member = members.findById(memberId).orElse(null);
        if (member == null) return RevokeResult.NOT_FOUND;
        if (member.getRole() != MemberRole.ADMIN) return RevokeResult.NOT_ADMIN;
        if (actor.memberId() != null && actor.memberId() == memberId) return RevokeResult.SELF;
        if (members.countByRoleAndStatus(MemberRole.ADMIN, MemberStatus.ACTIVE) <= 1) return RevokeResult.LAST_ADMIN;

        member.revokeAdmin();
        members.save(member);
        cutOffSessions(memberId, now());
        SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.ROLE_REVOKED, EventOutcome.SUCCESS)
                .memberId(memberId).reasonCode(clean(reason, null) == null
                        ? (actor.type() == AuditLog.ActorType.API ? "API_REVOKE" : "ADMIN_REVOKE") : "REASON_GIVEN"));
        audits.save(new AuditLog(event, actor.type(), actor.memberId(), memberId));
        return RevokeResult.REVOKED;
    }

    /** 관리자 권한을 가져도 되는 계정(허용 목록) 밖의 관리자 = 과잉권한. */
    @Transactional(readOnly = true)
    public List<Member> violations() {
        return members.search(null, MemberRole.ADMIN, null, PageRequest.of(0, 200)).getContent().stream()
                .filter(m -> !props.adminAllowlist().contains(m.getEmail().toLowerCase(java.util.Locale.ROOT))).toList();
    }

    @Transactional(readOnly = true)
    public Page<Member> search(String q, MemberRole role, MemberStatus status, int page, int size) {
        String like = q == null || q.isBlank() ? null : MemberAdminQuery.escapeLike(q.strip());
        return members.search(like, role, status, PageRequest.of(Math.max(page, 1) - 1, size));
    }

    /**
     * 관리자 화면의 회원 목록. state 는 all | ACTIVE | LOCKED(관리자 잠금) | AUTO(로그인 실패 자동 잠금) | ANY_LOCKED(잠긴 계정 전부).
     * 자동 잠금은 별도 표(account_lockout)에 있어서 잠긴 회원 번호를 모아 메모리에서 10건씩 자른다.
     */
    @Transactional(readOnly = true)
    public PagedList<Member> list(String q, MemberRole role, String state, com.san.bastionpay.member.MemberTier tier, int page) {
        String like = q == null || q.isBlank() ? null : MemberAdminQuery.escapeLike(q.strip());
        if ("AUTO".equals(state) || "ANY_LOCKED".equals(state)) {
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            lockoutIds.get().forEach(ids::add);
            if ("ANY_LOCKED".equals(state)) members.search(null, null, MemberStatus.LOCKED, PageRequest.of(0, 500)).forEach(m -> ids.add(m.getId()));
            String needle = q == null ? null : q.strip().toLowerCase(java.util.Locale.ROOT);
            List<Member> all = members.findAllById(ids).stream()
                    .filter(m -> role == null || m.getRole() == role)
                    .filter(m -> tier == null || m.getTier() == tier)
                    .filter(m -> needle == null || needle.isEmpty() || m.getNickname().toLowerCase(java.util.Locale.ROOT).contains(needle) || m.getEmail().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    .sorted(java.util.Comparator.comparingLong(Member::getId)).toList();
            return PagedList.of(all, page);
        }
        MemberStatus status = "ACTIVE".equals(state) ? MemberStatus.ACTIVE : "LOCKED".equals(state) ? MemberStatus.LOCKED : null;
        return PagedList.of(members.searchWithTier(like, role, status, tier, PageRequest.of(Math.max(page, 1) - 1, PagedList.PAGE_SIZE)));
    }

    /** 이메일 또는 회원 id 로 회원을 찾는다(자동화가 이메일만 알고 있을 때). */
    @Transactional(readOnly = true)
    public Optional<Member> find(Long memberId, String email) {
        if (memberId != null) return members.findById(memberId);
        if (email != null && !email.isBlank()) return members.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
        return Optional.empty();
    }

    /** 이 계정이 자동 잠금·수동 잠금 대상(합성 테스트 계정)인가. 화면이 잠금 버튼을 보일지 정할 때 쓴다. */
    public boolean lockable(Member member) {
        return lockout.applies(member);
    }

    public boolean overPrivileged(Member member) {
        return member.getRole() == MemberRole.ADMIN && !props.adminAllowlist().contains(member.getEmail().toLowerCase(java.util.Locale.ROOT));
    }

    private void cutOffSessions(long memberId, LocalDateTime now) {
        sessions.endAllForMember(memberId, now, "REVOKED");
        refreshTokens.revokeAllForMember(memberId, now);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String s = value.strip().replaceAll("[\\p{Cntrl}]", " ");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
