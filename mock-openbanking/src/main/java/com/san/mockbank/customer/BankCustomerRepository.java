package com.san.mockbank.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BankCustomerRepository extends JpaRepository<BankCustomer, Long> {

    Optional<BankCustomer> findByLoginId(String loginId);

    Optional<BankCustomer> findByPhone(String phone);

    Optional<BankCustomer> findByUserSeqNo(String userSeqNo);
}
