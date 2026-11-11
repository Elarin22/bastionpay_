package com.san.bastionpay.core.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class MailService {

    private static final String TEMPLATE = "mail/notice";
    private static final String SENDER_NAME = "BastionPay";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final ITemplateEngine templateEngine;
    private final MailProperties properties;
    private final String smtpUsername;

    public MailService(ObjectProvider<JavaMailSender> mailSender, ITemplateEngine templateEngine,
                       MailProperties properties, @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.properties = properties;
        this.smtpUsername = smtpUsername;
    }

    public void sendVerificationCode(String to, String code, Duration ttl) {
        MailContent content = verificationContent(code, ttl);
        send(to, content, "인증 코드 " + code);
    }

    static MailContent verificationContent(String code, Duration ttl) {
        String expiry = ttl.toMinutes() + "분 안에 가입 화면에 입력해 주세요. 시간이 지나면 새 코드를 받을 수 있습니다.";
        return new MailContent(
                "[BastionPay] 이메일 인증 코드",
                "인증 코드 " + code + " · " + ttl.toMinutes() + "분 안에 입력해 주세요.",
                "이메일 인증을 완료해 주세요",
                "회원가입 화면에 아래 인증 코드를 입력하면 다음 단계로 넘어갑니다.",
                code, expiry, List.of(),
                "본인이 요청하지 않았다면 이 메일을 무시해 주세요. 다른 사람에게 인증 코드를 알려 주지 마세요.");
    }

    static MailContent alreadyRegisteredContent() {
        return new MailContent(
                "[BastionPay] 이미 가입된 이메일입니다",
                "이 이메일로는 이미 가입된 계정이 있습니다.",
                "이미 가입된 이메일입니다",
                "이 이메일 주소로 회원가입 요청이 들어왔지만, 같은 주소로 가입된 계정이 이미 있습니다.",
                null, null,
                List.of("로그인 화면에서 이 이메일과 가입 때 정한 비밀번호로 로그인해 주세요.",
                        "새 계정을 만들 필요가 없습니다."),
                "본인이 가입을 시도한 것이 아니라면 이 메일을 무시해 주세요. 계정에는 아무 변화가 없습니다.");
    }

    String render(MailContent c) {
        Context ctx = new Context(Locale.KOREA);
        ctx.setVariable("subject", c.subject());
        ctx.setVariable("preheader", c.preheader());
        ctx.setVariable("heading", c.heading());
        ctx.setVariable("intro", c.intro());
        ctx.setVariable("code", c.code());
        ctx.setVariable("expiryNote", c.expiryNote());
        ctx.setVariable("steps", c.steps());
        ctx.setVariable("notice", c.notice());
        return templateEngine.process(TEMPLATE, ctx);
    }

    /** 이메일 클라이언트가 HTML 을 못 보여 줄 때를 위한 텍스트 본문 */
    static String plainText(MailContent c) {
        StringBuilder sb = new StringBuilder(c.heading()).append("\n\n").append(c.intro()).append("\n\n");
        if (c.code() != null) sb.append("인증 코드: ").append(c.code()).append("\n").append(c.expiryNote()).append("\n\n");
        c.steps().forEach(s -> sb.append("- ").append(s).append("\n"));
        return sb.append(c.steps().isEmpty() ? "" : "\n").append(c.notice()).toString();
    }

    private void send(String to, MailContent content, String devNote) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || smtpUsername.isBlank()) {
            // SMTP 계정 미설정. dev 에서만 코드를 로그로 확인할 수 있게 한다.
            if (properties.logCode()) {
                log.warn("메일 설정이 없어 발송하지 않음 (dev 전용 출력): {}", devNote);
                return;
            }
            throw new MailSendException("메일 발송 설정(MAIL_USERNAME, MAIL_PASSWORD)이 없습니다.");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.from(), SENDER_NAME);
            helper.setTo(to);
            helper.setSubject(content.subject());
            helper.setText(plainText(content), render(content));   // text + html 멀티파트
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            throw new MailSendException("메일 발송에 실패했습니다.", e);
        }
    }

    /** 메일 한 통의 내용. code, expiryNote 는 인증 메일에서만, steps 는 안내 메일에서만 채운다. */
    record MailContent(String subject, String preheader, String heading, String intro,
                       String code, String expiryNote, List<String> steps, String notice) {
    }
}
