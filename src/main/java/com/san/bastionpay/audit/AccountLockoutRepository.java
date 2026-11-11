package com.san.bastionpay.audit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountLockoutRepository extends JpaRepository<AccountLockout, Long> {

    /** 같은 계정의 동시 로그인 실패가 서로 덮어쓰지 않도록 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AccountLockout l where l.memberId = :memberId")
    Optional<AccountLockout> findForUpdate(@Param("memberId") Long memberId);

    /** 행이 없을 때만 만든다. 동시에 두 요청이 들어와도 충돌 예외 없이 한 행만 생긴다. */
    @Modifying
    @Query(value = "insert ignore into account_lockout (member_id, failed_count, updated_at) values (:memberId, 0, :now)", nativeQuery = true)
    int insertIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    @Query("select l from AccountLockout l where l.lockedUntil > :now order by l.lockedUntil desc")
    List<AccountLockout> findLocked(@Param("now") LocalDateTime now);
}
