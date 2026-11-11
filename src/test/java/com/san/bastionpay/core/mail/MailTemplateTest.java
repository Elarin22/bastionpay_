package com.san.bastionpay.core.mail;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailTemplateTest {

    private final MailService mailService = new MailService(
            (ObjectProvider<JavaMailSender>) null, engine(), new MailProperties("from@example.com", false), "");

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @Test
    void verificationMailShowsCodeAndExpiry() throws Exception {
        String html = mailService.render(MailService.verificationContent("482915", Duration.ofMinutes(10)));

        assertThat(html).contains("482915", "10분 안에", "이메일 인증을 완료해 주세요");
        assertThat(html).doesNotContain("th:text", "th:if", "th:each");
        write("verification.html", html);
    }

    @Test
    void alreadyRegisteredMailHasNoCode() throws Exception {
        String html = mailService.render(MailService.alreadyRegisteredContent());

        assertThat(html).contains("이미 가입된 이메일입니다", "로그인 화면에서");
        assertThat(html).doesNotContain("인증 코드</div>");
        write("already-registered.html", html);
    }

    @Test
    void userControlledValuesAreEscaped() {
        MailService.MailContent evil = new MailService.MailContent("s", "p", "<script>alert(1)</script>", "i",
                null, null, List.of("<b>x</b>"), "n");

        String html = mailService.render(evil);

        assertThat(html).doesNotContain("<script>alert").doesNotContain("<b>x</b>");
    }

    private static void write(String name, String html) throws Exception {
        Path dir = Path.of("build", "mail-preview");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), html);
    }
}
