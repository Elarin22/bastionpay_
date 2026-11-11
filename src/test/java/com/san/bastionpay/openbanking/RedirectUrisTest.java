package com.san.bastionpay.openbanking;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectUrisTest {

    private static final String LOCAL = "https://localhost:8080/openbanking/callback";
    private static final String LAN = "https://192.168.3.17:8080/openbanking/callback";

    private final RedirectUris uris = new RedirectUris(
            new OpenBankingProperties("b", "c", "s", LOCAL, "", "secret", Duration.ofMinutes(1),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), ""),
            List.of(LAN));

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setScheme(scheme);
        r.setServerName(host);
        r.setServerPort(port);
        return r;
    }

    @Test
    void picksTheAddressTheBrowserUsed() {
        assertThat(uris.forRequest(request("https", "localhost", 8080))).isEqualTo(LOCAL);
        assertThat(uris.forRequest(request("https", "192.168.3.17", 8080))).isEqualTo(LAN);
    }

    @Test
    void unknownHostFallsBackToTheDefault() {
        assertThat(uris.forRequest(request("https", "evil.example", 8080))).isEqualTo(LOCAL);
        assertThat(uris.forRequest(request("http", "192.168.3.17", 8080))).isEqualTo(LOCAL);
    }
}
