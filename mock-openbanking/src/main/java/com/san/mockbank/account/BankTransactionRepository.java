package com.san.mockbank.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    List<BankTransaction> findByAccountIdAndTranAtBetweenOrderByTranAtDesc(Long accountId, LocalDateTime from, LocalDateTime to);

    /** [from, to) 구간의 거래. 정렬과 페이지는 Pageable 로 정한다. */
    @Query("select t from BankTransaction t where t.accountId = :accountId and t.tranAt >= :from and t.tranAt < :to "
            + "and t.inoutType in :types")
    Page<BankTransaction> search(@Param("accountId") Long accountId, @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to, @Param("types") Collection<InOutType> types, Pageable pageable);
}
