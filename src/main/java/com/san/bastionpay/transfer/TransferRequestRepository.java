package com.san.bastionpay.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransferRequestRepository extends JpaRepository<TransferRequest, Long> {

    Optional<TransferRequest> findByMemberIdAndRequestId(Long memberId, String requestId);

    /** 완료된 이체의 금액 합(since 이후). 누적 이체 금액으로 등급을 정하는 데 쓴다. */
    @Query("select coalesce(sum(t.amount), 0) from TransferRequest t where t.memberId = :memberId and t.status = :status and t.createdAt >= :since")
    long sumAmount(@Param("memberId") Long memberId, @Param("status") TransferStatus status, @Param("since") java.time.LocalDateTime since);

    /** 사람이 확인해야 하는 이체(운영·관리자 화면용). */
    List<TransferRequest> findByStatusOrderByIdAsc(TransferStatus status);
}
