package com.san.mockbank.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountConsentRepository extends JpaRepository<AccountConsent, Long> {

    Optional<AccountConsent> findByClientPkAndAccountId(Long clientPk, Long accountId);

    List<AccountConsent> findByClientPk(Long clientPk);
}
