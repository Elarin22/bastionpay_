package com.san.bastionpay.admin;

import com.san.bastionpay.audit.Actor;
import com.san.bastionpay.audit.AuditLog;
import com.san.bastionpay.audit.AuditLogRepository;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.IpAddresses;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.member.MemberAdminQuery;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 차단(active response). 차단된 IP 의 요청은 IpBlockFilter 가 앱에 닿기 전에 403 으로 돌려보낸다.
 * 요청마다 DB 를 보지 않도록 차단 목록을 메모리에 두고, 차단·해제할 때 바로 고치며 30초마다 DB 와 맞춘다.
 * 지금 요청한 관리자 자신의 IP 는 차단하지 않는다(그 자리에서 관리자 화면을 잃는다).
 */
@Slf4j
@Service
public class BlockedIpService {

    public enum Result { BLOCKED, ALREADY_BLOCKED, INVALID_IP, SELF }

    private final BlockedIpRepository repository;
    private final SecurityEventRecorder events;
    private final AuditLogRepository audits;
    private volatile Set<String> cache = ConcurrentHashMap.newKeySet();

    public BlockedIpService(BlockedIpRepository repository, SecurityEventRecorder events, AuditLogRepository audits) {
        this.repository = repository;
        this.events = events;
        this.audits = audits;
    }

    @PostConstruct
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reload() {
        try {
            Set<String> fresh = ConcurrentHashMap.newKeySet();
            repository.findAll().forEach(b -> fresh.add(b.getIp()));
            cache = fresh;
        } catch (RuntimeException e) {
            log.error("차단 IP 목록을 불러오지 못했습니다. 기존 목록을 유지합니다.", e);
        }
    }

    /** 이 IP 가 차단 목록에 있는가(메모리 조회). */
    public boolean isBlocked(String ip) {
        if (ip == null) return false;
        String normalized = IpAddresses.normalize(ip).orElse(ip);
        return cache.contains(normalized);
    }

    /** IP 를 차단한다. requesterIp 는 지금 요청한 사람의 IP(자기 차단 방지). */
    @Transactional
    public Result block(String rawIp, String reason, Actor actor, String requesterIp) {
        String ip = IpAddresses.normalize(rawIp).orElse(null);
        if (ip == null) return Result.INVALID_IP;
        if (requesterIp != null && ip.equals(IpAddresses.normalize(requesterIp).orElse(requesterIp))) return Result.SELF;
        if (repository.existsById(ip)) return Result.ALREADY_BLOCKED;   // 처음 차단한 시각·사유를 덮어쓰지 않는다

        String why = reason == null || reason.isBlank() ? "차단 by " + actor.label() : reason.strip();
        repository.saveAndFlush(new BlockedIp(ip, why.length() > 200 ? why.substring(0, 200) : why, actor.label(), LocalDateTime.now(ZoneOffset.UTC)));
        cache.add(ip);
        SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.IP_BLOCKED, EventOutcome.SUCCESS)
                .sourceIp(ip).reasonCode(reasonCode(actor)));
        audits.save(new AuditLog(event, actor.type(), actor.memberId(), null));
        return Result.BLOCKED;
    }

    /** 차단을 푼다. 차단 목록에 없던 IP 면 false. 형식 검사는 하지 않는다(예전에 잘못 들어간 값도 지울 수 있어야 한다). */
    @Transactional
    public boolean unblock(String rawIp, Actor actor) {
        String ip = IpAddresses.normalize(rawIp).orElse(rawIp == null ? "" : rawIp.trim());
        if (!repository.existsById(ip)) return false;
        repository.deleteById(ip);
        repository.flush();
        cache.remove(ip);
        SecurityEvent event = events.record(SecurityEvent.of(SecurityEventType.IP_UNBLOCKED, EventOutcome.SUCCESS)
                .sourceIp(IpAddresses.normalize(ip).orElse(null)).reasonCode(reasonCodeUnblock(actor)));
        audits.save(new AuditLog(event, actor.type(), actor.memberId(), null));
        return true;
    }

    @Transactional(readOnly = true)
    public Page<BlockedIp> list(String q, int page, int size) {
        String like = q == null || q.isBlank() ? null : MemberAdminQuery.escapeLike(q.strip());
        return repository.search(like, PageRequest.of(Math.max(page, 1) - 1, size));
    }

    private static String reasonCode(Actor actor) {
        return actor.type() == AuditLog.ActorType.API ? "API_BLOCK" : "ADMIN_BLOCK";
    }

    private static String reasonCodeUnblock(Actor actor) {
        return actor.type() == AuditLog.ActorType.API ? "API_UNBLOCK" : "ADMIN_UNBLOCK";
    }
}
