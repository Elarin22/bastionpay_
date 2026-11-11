package com.san.bastionpay.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 사용 처리를 DB에서 원자적으로 한다. 같은 토큰으로 동시에 온 요청 중 하나만 1을 돌려받는다. */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.revokedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.memberId = :memberId and t.revokedAt is null")
    int revokeAllForMember(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
