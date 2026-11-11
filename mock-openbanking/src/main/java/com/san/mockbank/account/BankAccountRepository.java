package com.san.mockbank.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    List<BankAccount> findByCustomerIdAndStatusOrderByIdDesc(Long customerId, AccountStatus status);

    List<BankAccount> findByCustomerIdOrderByIdAsc(Long customerId);

    Optional<BankAccount> findByFintechUseNum(String fintechUseNum);

    Optional<BankAccount> findByBankCodeStdAndAccountNum(String bankCodeStd, String accountNum);

    /**
     * 이체에 쓰는 계좌들을 id 순서대로 잠근다(SELECT ... FOR UPDATE). 항상 같은 순서로 잠가서
     * 서로 반대 방향의 이체가 동시에 들어와도 교착이 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BankAccount a where a.id in :ids order by a.id")
    List<BankAccount> lockAllByIdIn(@Param("ids") Collection<Long> ids);
}
