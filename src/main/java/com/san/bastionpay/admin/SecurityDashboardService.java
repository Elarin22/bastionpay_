package com.san.bastionpay.admin;

import com.san.bastionpay.app.Pager;
import com.san.bastionpay.audit.AccountLockout;
import com.san.bastionpay.audit.AccountLockoutRepository;
import com.san.bastionpay.audit.LockoutProperties;
import com.san.bastionpay.audit.LoginSubjectHasher;
import com.san.bastionpay.audit.SecurityEventEntity;
import com.san.bastionpay.audit.SecurityEventRepository;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.audit.Severity;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRepository;
import com.san.bastionpay.transfer.TransferRequest;
import com.san.bastionpay.transfer.TransferRequestRepository;
import com.san.bastionpay.transfer.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 관리자 보안 대시보드가 보여 줄 데이터. security_event(보안 이벤트), account_lockout(잠금), audit_log(감사),
 * transfer_request(검토 필요 이체)를 읽기만 한다. 시각은 DB 에는 UTC 로 있고 화면에는 한국 시간으로 보여 준다.
 */
@Service
@Transactional(readOnly = true)
public class SecurityDashboardService {

    public static final int EVENTS_PER_PAGE = 20;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM.dd HH:mm:ss");

    /** 조회 기간. */
    public enum Range {
        HOUR("1h", "1시간", Duration.ofHours(1)),
        DAY("24h", "24시간", Duration.ofHours(24)),
        WEEK("7d", "7일", Duration.ofDays(7));

        private final String code;
        private final String label;
        private final Duration duration;

        Range(String code, String label, Duration duration) {
            this.code = code;
            this.label = label;
            this.duration = duration;
        }

        public String code() { return code; }
        public String label() { return label; }
        public Duration duration() { return duration; }

        public static Range of(String code) {
            for (Range r : values()) if (r.code.equals(code)) return r;
            return DAY;
        }
    }

    public record TypeRow(String type, long count, Severity severity) {}

    public record CountRow(String label, String detail, long count) {}

    public record EventRow(String time, String type, Severity severity, String outcome, String sourceIp, String member, String reason,
                           String target) {}

    public record ReviewRow(long transferId, long memberId, String from, long amount, String status, String code, String time) {}

    public record LockedRow(long memberId, String nickname, String email, String lockedUntil) {}

    /** 분류 하나의 건수와 그 안의 이벤트 종류별 건수. important 는 높음 이상(HIGH·CRITICAL)인 건수. */
    public record CategoryRow(String code, String label, long count, long important, List<TypeRow> types) {}

    /** 개요 화면: 훑어보는 요약만 담는다. 목록은 각 탭(이벤트·집계·감사 기록)에서 필터와 쪽 나누기로 본다. */
    public record Overview(Range range, long total, long important, List<CategoryRow> categories, PagedList<ReviewRow> reviews,
                           List<LockedRow> locked, long lockedTotal, List<CountRow> topIps, List<CountRow> topSubjects,
                           List<EventRow> recentImportant) {}

    private static final int PREVIEW = 5;

    private final SecurityEventRepository events;
    private final AccountLockoutRepository lockouts;
    private final MemberRepository members;
    private final TransferRequestRepository transfers;
    private final LockoutProperties lockoutProps;
    private final LoginSubjectHasher hasher;

    public SecurityDashboardService(SecurityEventRepository events, AccountLockoutRepository lockouts, MemberRepository members,
                                    TransferRequestRepository transfers, LockoutProperties lockoutProps, LoginSubjectHasher hasher) {
        this.events = events;
        this.lockouts = lockouts;
        this.members = members;
        this.transfers = transfers;
        this.lockoutProps = lockoutProps;
        this.hasher = hasher;
    }

    public Overview load(Range range, int reviewPage) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime since = now.minus(range.duration);

        // 분류별 건수: 이벤트 종류별 건수를 분류로 묶는다
        Map<SecurityEventType, Long> counts = new java.util.EnumMap<>(SecurityEventType.class);
        events.countByType(since).forEach(c -> counts.put(c.getType(), c.getCnt()));
        Map<SecurityEventType, Long> important = new java.util.EnumMap<>(SecurityEventType.class);
        events.countBySeverityGroupedByType(since, EnumSet.of(Severity.HIGH, Severity.CRITICAL)).forEach(c -> important.put(c.getType(), c.getCnt()));
        List<CategoryRow> categories = new java.util.ArrayList<>();
        for (SecurityEventType.Category category : SecurityEventType.Category.values()) {
            List<TypeRow> typeRows = category.types().stream().filter(counts::containsKey)
                    .map(t -> new TypeRow(t.name(), counts.get(t), t.defaultSeverity()))
                    .sorted(java.util.Comparator.comparingLong(TypeRow::count).reversed()).toList();
            long count = typeRows.stream().mapToLong(TypeRow::count).sum();
            long high = category.types().stream().mapToLong(t -> important.getOrDefault(t, 0L)).sum();
            categories.add(new CategoryRow(category.name(), category.label(), count, high, typeRows));
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long importantTotal = important.values().stream().mapToLong(Long::longValue).sum();

        List<ReviewRow> reviews = transfers.findByStatusOrderByIdAsc(TransferStatus.NEEDS_REVIEW).stream().map(this::toReview).toList();

        List<LockedRow> locked = lockedAccounts(now);

        // 로그인 실패 상위 5: 같은 IP·같은 계정의 실패 횟수. 전체는 집계 탭에서 본다
        Map<String, String> knownSubjects = knownSubjects();
        var top = PageRequest.of(0, PREVIEW);
        List<CountRow> topIps = events.topSourceIps(SecurityEventType.LOGIN_FAILURE, since, top).stream()
                .map(c -> new CountRow(c.getItem(), null, c.getCnt())).toList();
        List<CountRow> topSubjects = events.topSubjects(SecurityEventType.LOGIN_FAILURE, since, top).stream()
                .map(c -> new CountRow(knownSubjects.getOrDefault(c.getItem(), "계정 " + c.getItem().substring(0, 8)), c.getItem().substring(0, 12), c.getCnt())).toList();

        List<EventRow> recent = events.findAll(importantSince(since), PageRequest.of(0, PREVIEW, Sort.by(Sort.Direction.DESC, "id")))
                .getContent().stream().map(SecurityDashboardService::toRow).toList();

        return new Overview(range, total, importantTotal, categories, PagedList.of(reviews, reviewPage), locked.stream().limit(PREVIEW).toList(),
                locked.size(), topIps, topSubjects, recent);
    }

    /** 로그인 실패 집계의 계정 식별값(HMAC) -> 이메일. 허용 목록(합성 테스트 계정)만 이름을 붙일 수 있다. */
    Map<String, String> knownSubjects() {
        Map<String, String> known = new HashMap<>();
        for (String email : lockoutProps.allowlist()) known.put(hasher.hash(email), email);   // 관리자 화면이라 이메일을 그대로 보여 준다
        return known;
    }

    /** 자동 잠금(로그인 실패)과 수동 잠금 계정 전부. */
    private List<LockedRow> lockedAccounts(LocalDateTime now) {
        List<AccountLockout> locked = lockouts.findLocked(now);
        Map<Long, Member> byId = new HashMap<>();
        members.findAllById(locked.stream().map(AccountLockout::getMemberId).toList()).forEach(m -> byId.put(m.getId(), m));
        List<LockedRow> rows = new java.util.ArrayList<>(locked.stream().map(l -> {
            Member m = byId.get(l.getMemberId());
            return new LockedRow(l.getMemberId(), m == null ? "(알 수 없음)" : m.getNickname(), m == null ? "" : m.getEmail(),
                    "로그인 실패 · " + kst(l.getLockedUntil()) + "까지");
        }).toList());
        members.search(null, null, com.san.bastionpay.member.MemberStatus.LOCKED, PageRequest.of(0, 200)).forEach(m ->
                rows.add(new LockedRow(m.getId(), m.getNickname(), m.getEmail(), "수동 잠금 · " + (m.getLockReason() == null ? "사유 없음" : m.getLockReason()))));
        return rows;
    }

    private static org.springframework.data.jpa.domain.Specification<SecurityEventEntity> importantSince(LocalDateTime since) {
        return (root, query, cb) -> cb.and(cb.greaterThanOrEqualTo(root.get("occurredAt"), since), root.get("severity").in(Severity.HIGH, Severity.CRITICAL));
    }

    static EventRow toRow(SecurityEventEntity e) {
        String target = e.getRequestPath() == null ? null : e.getHttpMethod() + " " + e.getRequestPath();
        if (target == null && e.getTransferId() != null) target = "이체 #" + e.getTransferId();
        return new EventRow(kst(e.getOccurredAt()), e.getEventType().name(), e.getSeverity(), e.getOutcome().name(), e.getSourceIp(),
                e.getMemberId() == null ? null : "#" + e.getMemberId(), e.getReasonCode(), target);
    }

    private ReviewRow toReview(TransferRequest t) {
        return new ReviewRow(t.getId(), t.getMemberId(), t.getFromLabel(), t.getAmount(), t.getStatus().name(), t.getFailureCode(),
                t.getUpdatedAt() == null ? "-" : t.getUpdatedAt().format(TIME));   // 이체 기록의 시각은 서버 지역 시간(한국)이다
    }

    /** DB 의 UTC 시각을 한국 시간 문자열로. */
    public static String kst(LocalDateTime utc) {
        return utc == null ? "-" : utc.atZone(ZoneOffset.UTC).withZoneSameInstant(KST).format(TIME);
    }

    /** te***@test.com 처럼 앞 두 글자만 남긴다. 서버 간 API 의 응답처럼 이메일이 관리자 화면 밖으로 나가는 곳에서 쓴다. */
    public static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at < 1) return "***";
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    static SecurityEventType parseType(String value) {
        if (value == null || value.isBlank() || "all".equals(value)) return null;
        try {
            return SecurityEventType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
