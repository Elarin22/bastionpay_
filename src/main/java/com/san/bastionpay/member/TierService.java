package com.san.bastionpay.member;

import com.san.bastionpay.audit.AuditLog;
import com.san.bastionpay.audit.AuditLogRepository;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.transfer.TransferRequestRepository;
import com.san.bastionpay.transfer.TransferStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;

/**
 * 회원 등급 관리.
 * 승급: 완료된 이체의 누적 금액(등급 기준 시각 이후)이 기준을 넘으면 자동으로 오른다. 누적은 승급해도 이어서 센다.
 * 강등: 마지막 이용(또는 마지막 등급 변경) 후 inactivityMonths 개월이 지날 때마다 한 단계씩 내려가고, 누적은 그 시각부터 다시 센다.
 *       로그인할 때와 매일 새벽 배치에서 확인한다(앱이 꺼져 있던 동안 쌓인 기간도 놓치지 않는다).
 * 등급이 바뀌면 보안 이벤트(TIER_CHANGED)와 감사 기록을 남긴다.
 */
@Slf4j
@Service
public class TierService {

    /** 이용 기록을 DB 에 반영하는 최소 간격. 강등 기준이 개월 단위라 하루에 한 번이면 충분하다. */
    private static final Duration ACTIVITY_WRITE_INTERVAL = Duration.ofHours(24);

    /** 한 회원의 등급 현황. limit 이 비어 있으면 제한 없음. nextThreshold 가 비어 있으면 최고 등급. */
    public record Status(MemberTier tier, OptionalLong limit, long cumulative, MemberTier next, OptionalLong nextThreshold) {
        public long remainingToNext() {
            return nextThreshold.isPresent() ? Math.max(0, nextThreshold.getAsLong() - cumulative) : 0;
        }
    }

    private final MemberRepository members;
    private final TransferRequestRepository transfers;
    private final TierProperties props;
    private final SecurityEventRecorder events;
    private final AuditLogRepository audits;

    public TierService(MemberRepository members, TransferRequestRepository transfers, TierProperties props,
                       SecurityEventRecorder events, AuditLogRepository audits) {
        this.members = members;
        this.transfers = transfers;
        this.props = props;
        this.events = events;
        this.audits = audits;
    }

    /** 이 등급의 1회 이체 한도(비어 있으면 제한 없음). */
    public OptionalLong limitFor(MemberTier tier) {
        return props.limitFor(tier);
    }

    /** 회원의 현재 1회 이체 한도. 토큰이 아니라 DB 의 등급을 본다(승급·강등이 바로 반영된다). */
    @Transactional(readOnly = true)
    public OptionalLong limitOf(long memberId) {
        return members.findById(memberId).map(m -> props.limitFor(m.getTier())).orElse(OptionalLong.of(props.standardLimit()));
    }

    @Transactional(readOnly = true)
    public Status status(long memberId) {
        Member m = members.findById(memberId).orElse(null);
        if (m == null) {   // 회원 기록이 없으면(있을 수 없는 경우) 가장 낮은 등급으로 보여 준다
            return new Status(MemberTier.STANDARD, props.limitFor(MemberTier.STANDARD), 0, MemberTier.VIP, props.thresholdOfNext(MemberTier.STANDARD));
        }
        long cumulative = cumulative(m);
        MemberTier next = m.getTier() == MemberTier.VVIP ? null : MemberTier.values()[m.getTier().ordinal() + 1];
        return new Status(m.getTier(), props.limitFor(m.getTier()), cumulative, next, props.thresholdOfNext(m.getTier()));
    }

    /** 이체가 완료된 뒤 호출한다. 누적이 기준을 넘었으면 승급시킨다. 이체 자체에는 영향을 주지 않는다. */
    @Transactional
    public void onTransferCompleted(long memberId) {
        Member m = members.findById(memberId).orElse(null);
        if (m == null) return;
        MemberTier target = props.tierFor(cumulative(m));
        if (target.compareTo(m.getTier()) > 0) {
            m.promoteTo(target, LocalDateTime.now());
            members.save(m);
            record(m, "PROMOTED_BY_VOLUME");
        }
    }

    /** 로그인할 때: 이전 이용 시각으로 강등 여부를 먼저 보고, 그다음 이용 시각을 지금으로 갱신한다. */
    @Transactional
    public void recordLogin(Member member) {
        Member m = members.findById(member.getId()).orElse(member);
        LocalDateTime now = LocalDateTime.now();
        evaluateInactivity(m, now);
        m.recordActivity(now);
        members.save(m);
    }

    /** 화면을 이용할 때(세션이 연장될 때): 하루에 한 번만 이용 시각을 갱신한다. */
    @Transactional
    public void recordActivity(long memberId) {
        LocalDateTime now = LocalDateTime.now();
        members.touchActivity(memberId, now, now.minus(ACTIVITY_WRITE_INTERVAL));
    }

    /** 매일 새벽: 오래 이용하지 않은 회원의 등급을 한 단계씩 내린다. */
    @Scheduled(cron = "0 50 4 * * *")
    @Transactional
    public void demoteInactive() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(props.inactivityMonths(), ChronoUnit.MONTHS);
        int changed = 0;
        for (Member m : members.findDemotionCandidates(MemberTier.STANDARD, cutoff)) if (evaluateInactivity(m, now)) changed++;
        if (changed > 0) log.info("오래 이용하지 않은 회원 {}명의 등급을 내렸습니다.", changed);
    }

    /** 등급이 내려갔으면 true. 기간이 여러 번 지났으면 그만큼 한 번에 내린다(STANDARD 아래로는 내려가지 않는다). */
    boolean evaluateInactivity(Member m, LocalDateTime now) {
        if (m.getTier() == MemberTier.STANDARD) return false;
        LocalDateTime reference = m.getTierChangedAt() != null && m.getTierChangedAt().isAfter(m.getLastActiveAt()) ? m.getTierChangedAt() : m.getLastActiveAt();
        long periods = ChronoUnit.MONTHS.between(reference, now) / props.inactivityMonths();
        if (periods < 1) return false;
        MemberTier lowered = MemberTier.values()[(int) Math.max(0, m.getTier().ordinal() - periods)];
        m.demoteTo(lowered, now);
        members.save(m);
        record(m, "DEMOTED_INACTIVE");
        return true;
    }

    private long cumulative(Member m) {
        return transfers.sumAmount(m.getId(), TransferStatus.COMPLETED, m.getTierBaselineAt());
    }

    private void record(Member m, String reason) {
        SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.TIER_CHANGED, EventOutcome.SUCCESS).memberId(m.getId()).reasonCode(reason));
        audits.save(new AuditLog(event, AuditLog.ActorType.SYSTEM, null, m.getId()));
    }
}
