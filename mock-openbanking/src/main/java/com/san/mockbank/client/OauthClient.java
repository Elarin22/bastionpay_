package com.san.mockbank.client;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;


/** 가짜 오픈뱅킹을 이용하는 기관(앱). client secret 은 해시만 가진다. */
@Entity
@Table(name = "oauth_client")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OauthClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, updatable = false, length = 40)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, length = 100)
    private String clientSecretHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "org_code", nullable = false, updatable = false, length = 10, columnDefinition = "char(10)")
    private String orgCode;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 약정계좌. 이용기관이 소유한 bank_account 이다(출금이체의 수취 계좌, 입금이체의 출금 계좌). */
    @Column(name = "escrow_account_id")
    private Long escrowAccountId;

    @Column(name = "transfer_pass_phrase_hash", length = 100)
    private String transferPassPhraseHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_client_redirect_uri", joinColumns = @JoinColumn(name = "client_pk"))
    @Column(name = "redirect_uri", nullable = false, length = 300)
    private Set<String> redirectUris = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public OauthClient(String clientId, String clientSecretHash, String name, String orgCode, Set<String> redirectUris) {
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.name = name;
        this.orgCode = orgCode;
        this.redirectUris = new HashSet<>(redirectUris);
        this.createdAt = LocalDateTime.now();
    }

    public void registerEscrow(Long escrowAccountId, String transferPassPhraseHash) {
        this.escrowAccountId = escrowAccountId;
        this.transferPassPhraseHash = transferPassPhraseHash;
    }

    /** 등록된 redirect URI 와 글자 하나까지 같아야 한다. */
    public boolean allowsRedirectUri(String uri) {
        return uri != null && redirectUris.contains(uri);
    }
}
