package com.san.bastionpay.admin;

import com.san.bastionpay.audit.SecurityEventRepository;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 집계 탭: 같은 종류의 이벤트를 IP·로그인 계정·회원별로 센다(많은 순). 최소 횟수로 걸러 10건씩 본다.
 * 집계 결과는 최대 1,000개 묶음까지만 가져온다(그 이상은 기간을 줄여 보게 한다).
 */
@Service
@Transactional(readOnly = true)
public class SecurityStatsService {

    /** 무엇별로 셀지. */
    public enum By { IP, ACCOUNT, MEMBER }

    /** key 는 IP 또는 회원 번호(조치 버튼에 쓰는 값). detail 은 보조 설명. */
    public record Group(String label, String detail, String key, long count, String last) {}

    private static final int MAX_GROUPS = 1000;

    private final SecurityEventRepository events;
    private final MemberRepository members;
    private final SecurityDashboardService dashboard;

    public SecurityStatsService(SecurityEventRepository events, MemberRepository members, SecurityDashboardService dashboard) {
        this.events = events;
        this.members = members;
        this.dashboard = dashboard;
    }

    public PagedList<Group> groups(By by, SecurityEventType type, SecurityDashboardService.Range range, long minCount, int page) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minus(range.duration());
        var cap = PageRequest.of(0, MAX_GROUPS);
        List<Group> all = switch (by) {
            case IP -> events.topSourceIps(type, since, cap).stream()
                    .map(c -> new Group(c.getItem(), null, c.getItem(), c.getCnt(), SecurityDashboardService.kst(c.getLast()))).toList();
            case ACCOUNT -> {
                Map<String, String> known = dashboard.knownSubjects();
                yield events.topSubjects(type, since, cap).stream()
                        .map(c -> new Group(known.getOrDefault(c.getItem(), "계정 " + c.getItem().substring(0, 8)), "식별값 " + c.getItem().substring(0, 12),
                                null, c.getCnt(), SecurityDashboardService.kst(c.getLast()))).toList();
            }
            case MEMBER -> {
                var rows = events.topMembers(type, since, cap);
                Map<Long, Member> byId = new HashMap<>();
                members.findAllById(rows.stream().map(r -> r.getItem()).toList()).forEach(m -> byId.put(m.getId(), m));
                yield rows.stream().map(c -> {
                    Member m = byId.get(c.getItem());
                    return new Group(m == null ? "회원 #" + c.getItem() : m.getNickname(), m == null ? null : "#" + c.getItem() + " · " + m.getEmail(),
                            String.valueOf(c.getItem()), c.getCnt(), SecurityDashboardService.kst(c.getLast()));
                }).toList();
            }
        };
        return PagedList.of(all.stream().filter(g -> g.count() >= minCount).toList(), page);
    }
}
