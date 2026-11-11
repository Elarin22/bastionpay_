package com.san.mockbank.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccessTokenRepository extends JpaRepository<AccessToken, Long> {

    Optional<AccessToken> findByTokenHash(String tokenHash);
}
