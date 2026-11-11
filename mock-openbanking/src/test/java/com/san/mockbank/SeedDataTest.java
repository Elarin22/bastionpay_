package com.san.mockbank;

import com.san.mockbank.client.OauthClientRepository;
import com.san.mockbank.customer.BankCustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** db/seed/mockbank.sql 을 적용한 개발 DB 의 더미 데이터가 문서에 적힌 값과 맞는지 확인한다. */
@SpringBootTest
class SeedDataTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Autowired OauthClientRepository clients;
    @Autowired BankCustomerRepository customers;

    @Test
    void documentedClientCredentialsMatchSeededHashes() {
        var client = clients.findByClientId("bastionpay-dev-client").orElseThrow();

        assertThat(encoder.matches("bastionpay-dev-secret", client.getClientSecretHash())).isTrue();
        assertThat(encoder.matches("bastionpay-dev-pass", client.getTransferPassPhraseHash())).isTrue();
        assertThat(client.getOrgCode()).isEqualTo("M202610001");
        assertThat(client.allowsRedirectUri("https://localhost:8080/openbanking/callback")).isTrue();
        assertThat(client.getEscrowAccountId()).isNotNull();
    }

    @Test
    void sampleCustomersHaveTheDocumentedDummyPhoneNumbers() {
        assertThat(customers.findByPhone("01000000002").orElseThrow().getLoginId()).isEqualTo("test@test.com");
        assertThat(customers.findByPhone("01000000003").orElseThrow().getLoginId()).isEqualTo("vip@test.com");
        assertThat(customers.findByPhone("01000000004").orElseThrow().getLoginId()).isEqualTo("vvip@test.com");
        // 약정계좌 소유자는 잠겨 있어 인증할 수 없다
        var institution = customers.findByPhone("01000000001").orElseThrow();
        assertThat(institution.getLoginId()).isEqualTo("inst-bastionpay");
        assertThat(institution.isActive()).isFalse();
    }
}
