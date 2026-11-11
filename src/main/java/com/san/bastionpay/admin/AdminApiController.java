package com.san.bastionpay.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.san.bastionpay.audit.Actor;
import com.san.bastionpay.audit.IpAddresses;
import com.san.bastionpay.audit.Severity;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRole;
import com.san.bastionpay.member.MemberStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서버 간 호출용 보안 대응 API(나중에 n8n·회수봇 등이 부르는 입구). 인증은 X-API-Key 헤더 하나다.
 * 키를 설정하지 않았으면(SECURITY_API_KEY 비어 있음) 모든 요청이 401 이다. 이 앱은 바깥으로 호출하지 않고 받기만 한다.
 * 관리자 화면과 같은 서비스를 쓰므로 규칙(자기 차단 방지, 잠금 대상 제한 등)이 똑같다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private static final int MAX_PER_PAGE = 100;

    public record IncidentRequest(@JsonProperty("src_ip") String srcIp, String title, String severity, Integer hours) {}
    public record IdRequest(Long id) {}
    public record IdsRequest(List<Long> ids, @JsonProperty("all_open") Boolean allOpen, String severity) {}
    public record IpRequest(String ip, String reason) {}
    public record MemberRequest(@JsonProperty("member_id") Long memberId, String email, String reason) {}

    /** 인증 실패를 401 JSON 으로 바꾸기 위한 내부 예외. */
    private static final class Unauthorized extends RuntimeException {
        Unauthorized() {
            super(null, null, false, false);
        }
    }

    private final AdminApiAuth auth;
    private final IncidentService incidents;
    private final BlockedIpService blocked;
    private final MemberAdminService members;

    public AdminApiController(AdminApiAuth auth, IncidentService incidents, BlockedIpService blocked, MemberAdminService members) {
        this.auth = auth;
        this.incidents = incidents;
        this.blocked = blocked;
        this.members = members;
    }

    @ExceptionHandler(Unauthorized.class)
    ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("msg", "API 키가 없거나 잘못되었습니다."));
    }

    private Actor actor(HttpServletRequest request) {
        return auth.authenticate(request).orElseThrow(Unauthorized::new);
    }

    // ---- 인시던트 티켓 ----

    /** 티켓 생성/갱신. 같은 출발지 IP 의 열린 티켓이 있으면 갱신한다. 요약은 그 IP 의 보안 이벤트에서 자동으로 만든다. */
    @PostMapping("/incident")
    public ResponseEntity<Map<String, Object>> createIncident(HttpServletRequest request, @RequestBody IncidentRequest body) {
        Actor actor = actor(request);
        String ip = body == null ? null : IpAddresses.normalize(body.srcIp()).orElse(null);
        if (ip == null) return bad("src_ip(IP 주소)는 필수입니다.");
        Severity severity = null;
        if (body.severity() != null && !body.severity().isBlank()) {
            severity = AdminTicketsController.parseSeverity(body.severity().toUpperCase());
            if (severity == null || !AdminTicketsController.TICKET_SEVERITIES.contains(severity)) return bad("severity 는 LOW, MEDIUM, HIGH, CRITICAL 중 하나입니다.");
        }
        var result = incidents.create(ip, body.title(), severity, body.hours(), actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("msg", result.created() ? "인시던트 생성" : "인시던트 갱신");
        out.put("created", result.created());
        out.put("incident", incident(result.incident()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(out);
    }

    @GetMapping("/incidents")
    public Map<String, Object> listIncidents(HttpServletRequest request, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String severity, @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(name = "per_page", defaultValue = "20") int perPage) {
        actor(request);
        Page<Incident> result = incidents.list(AdminTicketsController.parseStatus(status == null ? null : status.toUpperCase()),
                AdminTicketsController.parseSeverity(severity == null ? null : severity.toUpperCase()), page, clamp(perPage));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", result.getNumberOfElements());
        out.put("incidents", result.getContent().stream().map(AdminApiController::incident).toList());
        out.putAll(paging(result));
        return out;
    }

    @PostMapping("/incident/close")
    public ResponseEntity<Map<String, Object>> closeIncident(HttpServletRequest request, @RequestBody IdRequest body) {
        Actor actor = actor(request);
        if (body == null || body.id() == null) return bad("id 는 필수입니다.");
        boolean closed = incidents.close(body.id(), actor);
        return closed ? ResponseEntity.ok(Map.of("msg", "인시던트 종료", "closed", true))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("msg", "없거나 이미 종료된 인시던트입니다.", "closed", false));
    }

    @PostMapping("/incidents/close")
    public ResponseEntity<Map<String, Object>> closeIncidents(HttpServletRequest request, @RequestBody IdsRequest body) {
        Actor actor = actor(request);
        if (body != null && Boolean.TRUE.equals(body.allOpen())) {
            // 조건(severity)에 맞는 열린 티켓 전부. 심각도를 주지 않으면 열린 티켓 전체
            Severity severity = body.severity() == null || body.severity().isBlank() ? null : AdminTicketsController.parseSeverity(body.severity().toUpperCase());
            if (body.severity() != null && !body.severity().isBlank() && (severity == null || !AdminTicketsController.TICKET_SEVERITIES.contains(severity))) {
                return bad("severity 는 LOW, MEDIUM, HIGH, CRITICAL 중 하나입니다.");
            }
            int closedAll = incidents.closeAllOpen(severity, actor);
            return ResponseEntity.ok(Map.of("msg", closedAll + "건 종료", "count", closedAll));
        }
        if (body == null || body.ids() == null || body.ids().isEmpty()) return bad("ids(1개 이상) 또는 all_open=true 가 필요합니다.");
        int closed = incidents.closeMany(body.ids(), actor);
        return ResponseEntity.ok(Map.of("msg", closed + "건 종료", "count", closed));
    }

    // ---- IP 차단 ----

    @PostMapping("/block")
    public ResponseEntity<Map<String, Object>> block(HttpServletRequest request, @RequestBody IpRequest body) {
        Actor actor = actor(request);
        // 자동화가 부르는 입구라 호출한 쪽 IP 를 막지 않는다(유효한 키를 가진 요청은 IP 차단의 영향을 받지 않는다)
        var result = blocked.block(body == null ? null : body.ip(), body == null ? null : body.reason(), actor, null);
        return switch (result) {
            case BLOCKED -> ResponseEntity.ok(Map.of("msg", "IP 차단 완료", "blocked", true, "changed", true));
            case ALREADY_BLOCKED -> ResponseEntity.ok(Map.of("msg", "이미 차단된 IP", "blocked", true, "changed", false));
            case INVALID_IP, SELF -> bad("ip(IP 주소)는 필수입니다.");
        };
    }

    @PostMapping("/unblock")
    public ResponseEntity<Map<String, Object>> unblock(HttpServletRequest request, @RequestBody IpRequest body) {
        Actor actor = actor(request);
        if (body == null || body.ip() == null || body.ip().isBlank()) return bad("ip 는 필수입니다.");
        boolean changed = blocked.unblock(body.ip(), actor);
        return ResponseEntity.ok(Map.of("msg", changed ? "차단 해제 완료" : "차단 목록에 없는 IP", "blocked", false, "changed", changed));
    }

    @GetMapping("/blocked")
    public Map<String, Object> listBlocked(HttpServletRequest request, @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "1") int page, @RequestParam(name = "per_page", defaultValue = "20") int perPage) {
        actor(request);
        Page<BlockedIp> result = blocked.list(q, page, clamp(perPage));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", result.getNumberOfElements());
        out.put("blocked", result.getContent().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", b.getIp());
            m.put("reason", b.getReason());
            m.put("blocked_by", b.getBlockedBy());
            m.put("blocked_at", b.getBlockedAt().toString() + "Z");
            return m;
        }).toList());
        out.putAll(paging(result));
        return out;
    }

    // ---- 계정 잠금·해제, 권한 회수 ----

    @PostMapping("/lock")
    public ResponseEntity<Map<String, Object>> lock(HttpServletRequest request, @RequestBody MemberRequest body) {
        Actor actor = actor(request);
        Member member = body == null ? null : members.find(body.memberId(), body.email()).orElse(null);
        if (member == null) return notFound();
        return switch (members.lock(member.getId(), body.reason(), actor)) {
            case LOCKED -> ResponseEntity.ok(Map.of("msg", "계정 잠금 완료", "locked", true, "changed", true));
            case ALREADY_LOCKED -> ResponseEntity.ok(Map.of("msg", "이미 잠긴 계정", "locked", true, "changed", false));
            case NOT_FOUND -> notFound();
            // 자동 잠금 대상(합성 테스트 계정) 밖의 계정은 잠그지 않는다. 잠갔다고 응답하지도 않는다
            case NOT_ALLOWED, SELF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("msg", "잠글 수 없는 계정입니다(합성 테스트 계정만 가능).", "locked", false, "changed", false));
            case NOT_ACTIVE -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("msg", "활성 상태가 아닌 계정입니다.", "locked", false, "changed", false));
        };
    }

    @PostMapping("/unlock")
    public ResponseEntity<Map<String, Object>> unlock(HttpServletRequest request, @RequestBody MemberRequest body) {
        Actor actor = actor(request);
        Member member = body == null ? null : members.find(body.memberId(), body.email()).orElse(null);
        if (member == null) return notFound();
        return switch (members.unlock(member.getId(), actor)) {
            case UNLOCKED -> ResponseEntity.ok(Map.of("msg", "잠금 해제 완료", "locked", false, "changed", true));
            case NOT_LOCKED -> ResponseEntity.ok(Map.of("msg", "잠겨 있지 않은 계정", "locked", false, "changed", false));
            case NOT_FOUND -> notFound();
        };
    }

    @PostMapping("/revoke")
    public ResponseEntity<Map<String, Object>> revoke(HttpServletRequest request, @RequestBody MemberRequest body) {
        Actor actor = actor(request);
        Member member = body == null ? null : members.find(body.memberId(), body.email()).orElse(null);
        if (member == null) return notFound();
        return switch (members.revokeAdmin(member.getId(), body.reason(), actor)) {
            case REVOKED -> ResponseEntity.ok(Map.of("msg", "권한 회수 완료", "revoked", true, "changed", true));
            case NOT_ADMIN -> ResponseEntity.ok(Map.of("msg", "이미 일반 권한(회수 불필요)", "revoked", false, "changed", false));
            case NOT_FOUND -> notFound();
            case LAST_ADMIN, SELF -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("msg", "마지막 관리자의 권한은 회수할 수 없습니다.", "revoked", false, "changed", false));
        };
    }

    /** 회원 목록(자동화가 상태를 읽는 용도). 이메일은 가려서 돌려준다. */
    @GetMapping("/users")
    public Map<String, Object> users(HttpServletRequest request, @RequestParam(required = false) String q, @RequestParam(required = false) String role,
                                     @RequestParam(required = false) String locked, @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(name = "per_page", defaultValue = "20") int perPage) {
        actor(request);
        MemberRole r = role == null ? null : parseEnum(MemberRole.class, role.toUpperCase());
        MemberStatus s = "1".equals(locked) ? MemberStatus.LOCKED : "0".equals(locked) ? MemberStatus.ACTIVE : null;
        Page<Member> result = members.search(q, r, s, page, clamp(perPage));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", result.getNumberOfElements());
        out.put("users", result.getContent().stream().map(AdminApiController::user).toList());
        out.putAll(paging(result));
        return out;
    }

    /** 관리자 권한을 가져도 되는 계정(허용 목록) 밖의 관리자. */
    @GetMapping("/violations")
    public Map<String, Object> violations(HttpServletRequest request) {
        actor(request);
        List<Map<String, Object>> list = members.violations().stream().map(AdminApiController::user).toList();
        return Map.of("count", list.size(), "violations", list);
    }

    // ---- 변환 도우미 ----

    private static Map<String, Object> incident(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("title", i.getTitle());
        m.put("src_ip", i.getSrcIp());
        m.put("severity", i.getSeverity().name());
        m.put("status", i.getStatus().name());
        m.put("summary", i.getSummary());
        m.put("event_count", i.getEventCount());
        m.put("actions", i.getActions());
        m.put("created_by", i.getCreatedBy());
        m.put("closed_by", i.getClosedBy());
        m.put("created_at", i.getCreatedAt().toString() + "Z");
        m.put("updated_at", i.getUpdatedAt().toString() + "Z");
        m.put("closed_at", i.getClosedAt() == null ? null : i.getClosedAt().toString() + "Z");
        return m;
    }

    private static Map<String, Object> user(Member m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("nickname", m.getNickname());
        out.put("email_masked", SecurityDashboardService.maskEmail(m.getEmail()));
        out.put("role", m.getRole().name());
        out.put("tier", m.getTier().name());
        out.put("status", m.getStatus().name());
        out.put("lock_reason", m.getLockReason());
        return out;
    }

    private static Map<String, Object> paging(Page<?> page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("page", page.getNumber() + 1);
        m.put("per_page", page.getSize());
        m.put("total", page.getTotalElements());
        m.put("pages", page.getTotalPages());
        return m;
    }

    private static int clamp(int perPage) {
        return Math.min(Math.max(perPage, 1), MAX_PER_PAGE);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("msg", msg));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("msg", "회원을 찾을 수 없습니다."));
    }
}
