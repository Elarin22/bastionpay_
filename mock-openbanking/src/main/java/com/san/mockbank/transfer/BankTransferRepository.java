package com.san.mockbank.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BankTransferRepository extends JpaRepository<BankTransfer, Long> {

    Optional<BankTransfer> findByClientPkAndBankTranId(Long clientPk, String bankTranId);
}
