package com.san.mockbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** 브라우저용 에러 화면(Whitelabel 대신)과 API 의 JSON 에러가 나뉘는지, 템플릿이 모두 그려지는지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorPagesTest {

    @LocalServerPort
    int port;

    @Autowired
    SpringTemplateEngine templates;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void unknownPageShowsBankErrorPageNotWhitelabel() {
        var response = client().get().uri("/no-such-page").accept(MediaType.TEXT_HTML)
                .exchange((req, res) -> new Object[]{res.getStatusCode(), new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)});
        assertThat(((HttpStatus) response[0]).value()).isEqualTo(404);
        String html = (String) response[1];
        assertThat(html).contains("페이지를 찾을 수 없습니다").contains("가짜 은행").doesNotContain("Whitelabel", "Exception", "trace");
    }

    @Test
    void wrongMethodShowsGenericClientErrorPage() {
        var response = client().post().uri("/oauth/2.0/authorize").accept(MediaType.TEXT_HTML)
                .exchange((req, res) -> new Object[]{res.getStatusCode().value(), new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)});
        assertThat((int) response[0]).isBetween(400, 499);
        assertThat((String) response[1]).doesNotContain("Whitelabel").contains("처음부터 다시 시도해 주세요");
    }

    @Test
    void apiClientsStillGetJsonNotHtml() {
        var response = client().get().uri("/no-such-api").accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> new Object[]{res.getHeaders().getContentType(), new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)});
        assertThat(response[0].toString()).contains("json");
        assertThat((String) response[1]).doesNotContain("<html");
    }

    @Test
    void everyErrorTemplateRenders() {
        var app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        for (String name : new String[]{"error/404", "error/4xx", "error/5xx"}) {
            var context = new WebContext(app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.KOREA);
            String html = templates.process(name, context);
            assertThat(html).as(name).contains("<main>").contains("bank.css").doesNotContain("th:");
            if (name.endsWith("5xx")) assertThat(html).contains("일시적인 오류");
        }
    }
}
