package com.san.bastionpay.openbanking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OpenBankingAuthStateRepository extends JpaRepository<OpenBankingAuthState, Long> {

    Optional<OpenBankingAuthState> findByStateHash(String stateHash);

    /** 사용 처리와 삭제를 한 번에 한다. 같은 state 로 동시에 온 요청 중 하나만 1을 돌려받는다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from OpenBankingAuthState s where s.stateHash = :hash")
    int consume(@Param("hash") String hash);

    @Transactional
    @Modifying
    @Query("delete from OpenBankingAuthState s where s.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
