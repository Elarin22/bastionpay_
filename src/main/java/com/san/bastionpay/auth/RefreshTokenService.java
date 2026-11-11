package com.san.bastionpay.auth;

import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberRepository;
import com.san.bastionpay.member.MemberStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh Token Rotation.
 * <ul>
 *   <li>사용할 때마다 새 토큰을 발급하고 이전 토큰은 사용 처리한다.</li>
 *   <li>사용된 토큰이 다시 오면 탈취로 보고 같은 family 의 토큰을 모두 폐기한다.</li>
 *   <li>단, 방금(유예 시간 이내) 회전된 토큰은 브라우저의 동시 요청일 수 있어 폐기 없이 거절만 한다.</li>
 *   <li>만료 시각은 로그인 시점에 고정된다(회전해도 늘어나지 않음). 설정한 로그인 유지 시간(기본 30분)이 지나면 다시 로그인해야 한다.</li>
 * </ul>
 */
@Slf4j
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final MemberRepository members;
    private final JwtProperties props;
    private final SecurityEventRecorder events;

    public RefreshTokenService(RefreshTokenRepository tokens, MemberRepository members, JwtProperties props,
                               SecurityEventRecorder events) {
        this.events = events;
        this.tokens = tokens;
        this.members = members;
        this.props = props;
    }

    /** 로그인 시 새 family 로 발급한다. */
    @Transactional
    public Issued issue(Member member) {
        return create(member.getId(), UUID.randomUUID().toString(), LocalDateTime.now().plus(props.refreshTokenTtl()));
    }

    @Transactional
    public Rotation rotate(String rawToken) {
        LocalDateTime now = LocalDateTime.now();
        RefreshToken token = tokens.findByTokenHash(sha256(rawToken)).orElse(null);
        if (token == null || token.isRevoked() || token.isExpired(now)) {
            return Rotation.invalid();
        }
        if (token.isUsed()) {
            if (token.getUsedAt().plus(props.refreshReuseGrace()).isAfter(now)) {
                return Rotation.race();
            }
            tokens.revokeFamily(token.getFamilyId(), now);
            events.record(SecurityEvent.of(SecurityEventType.REFRESH_TOKEN_REUSE, EventOutcome.DENIED)
                    .memberId(token.getMemberId()).reasonCode("REUSE_DETECTED"));
            return Rotation.invalid();
        }
        if (tokens.markUsed(token.getId(), now) == 0) {
            return Rotation.race();
        }
        Member member = members.findById(token.getMemberId())
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE).orElse(null);
        if (member == null) {
            tokens.revokeFamily(token.getFamilyId(), now);
            return Rotation.invalid();
        }
        return Rotation.rotated(member, create(member.getId(), token.getFamilyId(), token.getExpiresAt()));
    }

    /** 이 토큰이 속한 로그인(family)의 id. 모르는 토큰이면 비어 있다. */
    @Transactional(readOnly = true)
    public java.util.Optional<String> familyIdOf(String rawToken) {
        return tokens.findByTokenHash(sha256(rawToken)).map(RefreshToken::getFamilyId);
    }

    /** 세션이 끝났을 때(활동 없음 만료 등): 그 로그인의 토큰을 모두 폐기한다. */
    @Transactional
    public void revokeFamily(String familyId) {
        tokens.revokeFamily(familyId, LocalDateTime.now());
    }

    /** 로그아웃: 이 토큰이 속한 family 전체를 폐기한다. */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        tokens.findByTokenHash(sha256(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), LocalDateTime.now()));
    }

    @Scheduled(cron = "0 20 * * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = tokens.deleteExpired(LocalDateTime.now());
        if (deleted > 0) log.debug("만료된 Refresh Token {}건 삭제", deleted);
    }

    private Issued create(Long memberId, String familyId, LocalDateTime expiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(sha256(raw), memberId, familyId, expiresAt));
        return new Issued(raw, expiresAt, familyId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** familyId 는 이 로그인의 세션 id 로도 쓴다(Access Token 의 sid 클레임). */
    public record Issued(String token, LocalDateTime expiresAt, String familyId) {
    }

    /**
     * ROTATED: 새 토큰 발급됨 / INVALID: 확정적으로 못 쓰는 토큰(쿠키를 지워도 됨) /
     * RACE: 방금 다른 요청이 회전함(쿠키를 건드리지 않고 이번 요청만 비인증으로 둔다).
     */
    public record Rotation(Outcome outcome, Member member, Issued issued) {
        public enum Outcome { ROTATED, INVALID, RACE }

        static Rotation rotated(Member member, Issued issued) { return new Rotation(Outcome.ROTATED, member, issued); }
        static Rotation invalid() { return new Rotation(Outcome.INVALID, null, null); }
        static Rotation race() { return new Rotation(Outcome.RACE, null, null); }
    }
}
