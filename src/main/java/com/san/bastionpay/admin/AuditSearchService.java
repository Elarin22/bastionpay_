package com.san.bastionpay.admin;

import com.san.bastionpay.audit.AuditLog;
import com.san.bastionpay.audit.AuditLogRepository;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 감사 기록 탭: 누가(시스템·관리자·API) 무엇을 했는지를 기간·행위자·조치·대상 회원으로 걸러 10건씩 본다. */
@Service
@Transactional(readOnly = true)
public class AuditSearchService {

    public record Filter(SecurityDashboardService.Range range, AuditLog.ActorType actorType, SecurityEventType action, Long targetMemberId) {}

    public record Row(String time, String actor, String action, String target, String reason, String sourceIp) {}

    private final AuditLogRepository audits;
    private final MemberRepository members;

    public AuditSearchService(AuditLogRepository audits, MemberRepository members) {
        this.audits = audits;
        this.members = members;
    }

    public PagedList<Row> search(Filter f, int page) {
        var sort = Sort.by(Sort.Direction.DESC, "id");
        var result = audits.findAll(spec(f), PageRequest.of(Math.max(page, 1) - 1, PagedList.PAGE_SIZE, sort));
        if (result.getTotalPages() > 0 && page > result.getTotalPages()) {
            result = audits.findAll(spec(f), PageRequest.of(result.getTotalPages() - 1, PagedList.PAGE_SIZE, sort));
        }
        Map<Long, Member> byId = new HashMap<>();
        List<Long> ids = result.getContent().stream().map(AuditLog::getActorMemberId).filter(java.util.Objects::nonNull).distinct().toList();
        members.findAllById(ids).forEach(m -> byId.put(m.getId(), m));
        List<Row> rows = result.getContent().stream().map(a -> new Row(SecurityDashboardService.kst(a.getOccurredAt()), actor(a, byId), a.getAction().name(),
                a.getTargetMemberId() == null ? "-" : "#" + a.getTargetMemberId(), a.getReasonCode(), a.getSourceIp())).toList();
        return new PagedList<>(rows, result.getNumber() + 1, Math.max(1, result.getTotalPages()), result.getTotalElements());
    }

    private static String actor(AuditLog a, Map<Long, Member> admins) {
        return switch (a.getActorType()) {
            case ADMIN -> "관리자 " + (a.getActorMemberId() != null && admins.containsKey(a.getActorMemberId()) ? admins.get(a.getActorMemberId()).getNickname() : "#" + a.getActorMemberId());
            case API -> "API";
            case SYSTEM -> "시스템";
        };
    }

    static Specification<AuditLog> spec(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), LocalDateTime.now(ZoneOffset.UTC).minus(f.range().duration())));
            if (f.actorType() != null) ps.add(cb.equal(root.get("actorType"), f.actorType()));
            if (f.action() != null) ps.add(cb.equal(root.get("action"), f.action()));
            if (f.targetMemberId() != null) ps.add(cb.equal(root.get("targetMemberId"), f.targetMemberId()));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
