package com.san.bastionpay.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface LoginSessionRepository extends JpaRepository<LoginSession, String> {

    /** 아직 살아 있는 세션의 활동 시각과 만료 시각을 민다. 이미 끝났거나 만료됐으면 0행. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LoginSession s set s.idleExpiresAt = :newExpiry, s.lastActivityAt = :now "
            + "where s.sessionId = :sid and s.endedAt is null and s.idleExpiresAt > :now")
    int touch(@Param("sid") String sid, @Param("now") LocalDateTime now, @Param("newExpiry") LocalDateTime newExpiry);

    /** 세션을 끝낸다. 이미 끝난 세션은 건드리지 않는다(0행). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LoginSession s set s.endedAt = :now, s.endReason = :reason where s.sessionId = :sid and s.endedAt is null")
    int end(@Param("sid") String sid, @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** 한 회원의 살아 있는 세션을 모두 끝낸다(계정 잠금·권한 회수 직후 기존 로그인을 끊는다). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LoginSession s set s.endedAt = :now, s.endReason = :reason where s.memberId = :memberId and s.endedAt is null")
    int endAllForMember(@Param("memberId") Long memberId, @Param("now") LocalDateTime now, @Param("reason") String reason);

    @Modifying
    @Query("delete from LoginSession s where s.idleExpiresAt < :before or s.endedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
