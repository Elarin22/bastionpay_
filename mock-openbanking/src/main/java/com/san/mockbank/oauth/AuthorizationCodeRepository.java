package com.san.mockbank.oauth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthorizationCodeRepository extends JpaRepository<AuthorizationCode, Long> {

    Optional<AuthorizationCode> findByCodeHash(String codeHash);

    /** 사용 처리를 DB에서 원자적으로 한다. 같은 코드로 동시에 온 요청 중 하나만 1을 돌려받는다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AuthorizationCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
