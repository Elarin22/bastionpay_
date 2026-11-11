package com.san.mockbank.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankTransferItemRepository extends JpaRepository<BankTransferItem, Long> {

    List<BankTransferItem> findByTransferIdOrderByTranNo(Long transferId);
}
