package com.san.bastionpay.signup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SignupRequestRepository extends JpaRepository<SignupRequest, Long> {

    Optional<SignupRequest> findByTokenHash(String tokenHash);

    /** 이 이메일로 이미 인증을 마치고 가입을 진행 중인(만료 전) 요청이 있는가. */
    boolean existsByEmailAndEmailVerifiedAtIsNotNullAndExpiresAtAfter(String email, LocalDateTime now);

    /** 위와 같되, 자기 자신의 요청은 뺀다(코드를 확인하는 시점의 동시 인증 방지). */
    boolean existsByEmailAndEmailVerifiedAtIsNotNullAndExpiresAtAfterAndIdNot(String email, LocalDateTime now, Long id);

    /** 코드 입력 시도 횟수를 DB에서 원자적으로 올린다. 동시 요청으로 횟수 제한을 피할 수 없게 한다. */
    @Modifying(clearAutomatically = true)
    @Query("update SignupRequest r set r.codeAttempts = r.codeAttempts + 1 where r.id = :id")
    int increaseAttempts(@Param("id") Long id);

    @Modifying
    @Query("delete from SignupRequest r where r.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
