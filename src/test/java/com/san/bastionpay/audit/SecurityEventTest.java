package com.san.bastionpay.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 이벤트 필드 이름·생략 규칙, 출발지 IP 판정, 계정 식별값. DB 없이 확인한다. */
class SecurityEventTest {

    @Test
    void jsonFieldsUseTheAgreedNamesAndOmitMissingValues() {
        SecurityEvent login = SecurityEvent.of(SecurityEventType.LOGIN_FAILURE, EventOutcome.FAILURE)
                .traceId("abc123").sourceIp("203.0.113.9").loginSubjectHash("h".repeat(64)).reasonCode("BAD_CREDENTIALS").build();

        assertThat(login.toFields().keySet()).containsExactly(
                "event_id", "event_type", "occurred_at", "trace_id", "source_ip", "login_subject_hash", "severity", "outcome", "reason_code");
        assertThat(login.toFields()).containsEntry("event_type", "LOGIN_FAILURE").containsEntry("severity", "LOW").containsEntry("outcome", "FAILURE");
        assertThat(login.toFields().get("occurred_at").toString()).endsWith("Z");   // UTC
        assertThat(login.toFields()).doesNotContainKeys("member_id", "account_ref", "transfer_id", "amount", "currency");

        SecurityEvent transfer = SecurityEvent.of(SecurityEventType.TRANSFER_COMPLETED, EventOutcome.SUCCESS)
                .memberId(7L).transferId(42L).accountRef("fin-***000102").amountKrw(5_000).build();
        assertThat(transfer.toFields()).containsEntry("member_id", 7L).containsEntry("transfer_id", 42L)
                .containsEntry("account_ref", "fin-***000102").containsEntry("amount", 5_000L).containsEntry("currency", "KRW");
    }

    @Test
    void everyEventTypeHasItsOwnDefaultSeverityAndTheFixedTwelveExist() {
        List<String> fixed = List.of("LOGIN_SUCCESS", "LOGIN_FAILURE", "AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "CSRF_REJECTED",
                "TRANSFER_REQUESTED", "TRANSFER_COMPLETED", "TRANSFER_FAILED", "TRANSFER_NEEDS_REVIEW", "TRANSFER_REFUNDED",
                "TRANSFER_REFUND_FAILED", "TRANSFER_REPLAY");
        assertThat(java.util.Arrays.stream(SecurityEventType.values()).map(Enum::name)).containsAll(fixed);
        for (SecurityEventType t : SecurityEventType.values()) assertThat(t.defaultSeverity()).isNotNull();
        assertThat(SecurityEventType.TRANSFER_REFUND_FAILED.defaultSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void requestPathHidesLongNumbersAndControlCharacters() {
        SecurityEvent e = SecurityEvent.of(SecurityEventType.ACCESS_DENIED, EventOutcome.DENIED)
                .request("GET", "/accounts/199900000000000000000102\n{\"x\":1}").build();
        assertThat(e.requestPath()).doesNotContain("1999", "\n").contains("***");
        assertThat(e.httpMethod()).isEqualTo("GET");
    }

    @Test
    void sourceIpIgnoresForwardedHeaderUnlessTheDirectPeerIsATrustedProxy() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.20");
        req.addHeader("X-Forwarded-For", "1.2.3.4");

        // 신뢰 프록시가 아니면 헤더를 무시한다(공격자가 헤더로 IP 를 속일 수 없다)
        assertThat(resolver(List.of()).resolve(req)).isEqualTo("198.51.100.20");
        assertThat(resolver(List.of("10.0.0.5")).resolve(req)).isEqualTo("198.51.100.20");

        // 신뢰 프록시에서 온 경우: 오른쪽부터 신뢰 프록시를 건너뛰고 처음 나오는 주소. 왼쪽에 끼워 넣은 값은 쓰지 않는다
        MockHttpServletRequest viaProxy = new MockHttpServletRequest();
        viaProxy.setRemoteAddr("10.0.0.5");
        viaProxy.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9, 10.0.0.5");
        assertThat(resolver(List.of("10.0.0.5")).resolve(viaProxy)).isEqualTo("203.0.113.9");

        // IP 형식이 아닌 값은 버리고 실제 주소를 쓴다
        MockHttpServletRequest garbage = new MockHttpServletRequest();
        garbage.setRemoteAddr("10.0.0.5");
        garbage.addHeader("X-Forwarded-For", "evil\\nvalue");
        assertThat(resolver(List.of("10.0.0.5")).resolve(garbage)).isEqualTo("10.0.0.5");
        assertThat(ClientIpResolver.isIp("999.1.1.1")).isFalse();
        assertThat(ClientIpResolver.isIp("2001:db8::1")).isTrue();
    }

    @Test
    void loginSubjectHashIsStableKeyedAndHidesTheEmail() {
        LoginSubjectHasher a = new LoginSubjectHasher(new SecurityProperties(List.of(), "key-one", true));
        LoginSubjectHasher b = new LoginSubjectHasher(new SecurityProperties(List.of(), "key-two", true));

        assertThat(a.hash("Test@Test.com ")).isEqualTo(a.hash("test@test.com")).hasSize(64).doesNotContain("test");
        assertThat(a.hash("test@test.com")).isNotEqualTo(a.hash("vip@test.com"));
        assertThat(a.hash("test@test.com")).isNotEqualTo(b.hash("test@test.com"));   // 키가 다르면 값도 다르다
    }

    private static ClientIpResolver resolver(List<String> trusted) {
        return new ClientIpResolver(new SecurityProperties(trusted, "k", true));
    }
}
