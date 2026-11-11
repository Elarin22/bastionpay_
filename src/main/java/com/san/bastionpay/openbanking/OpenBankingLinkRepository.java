package com.san.bastionpay.openbanking;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface OpenBankingLinkRepository extends JpaRepository<OpenBankingLink, Long> {

    Optional<OpenBankingLink> findByMemberId(Long memberId);

    Optional<OpenBankingLink> findByUserSeqNo(String userSeqNo);

    @Transactional
    void deleteByMemberId(Long memberId);
}
