package com.san.mockbank.api;

import org.springframework.data.jpa.repository.JpaRepository;


public interface ApiCallLogRepository extends JpaRepository<ApiCallLog, Long> {

    boolean existsByClientPkAndBankTranId(Long clientPk, String bankTranId);
}
