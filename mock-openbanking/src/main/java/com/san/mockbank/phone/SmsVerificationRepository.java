package com.san.mockbank.phone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SmsVerificationRepository extends JpaRepository<SmsVerification, Long> {

    Optional<SmsVerification> findFirstByPhoneOrderByIdDesc(String phone);

    long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime after);

    List<SmsVerification> findByPhoneAndConsumedAtIsNull(String phone);

    @Transactional
    @Modifying
    @Query("delete from SmsVerification v where v.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
