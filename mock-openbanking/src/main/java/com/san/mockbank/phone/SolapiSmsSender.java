package com.san.mockbank.phone;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 솔라피로 실제 문자를 보낸다(SOLAPI_ENABLED=true 일 때만). 건당 비용이 들고 실제 번호로 가므로
 * 더미 번호(0100000xxxx)에는 보내지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mockbank.sms", name = "enabled", havingValue = "true")
public class SolapiSmsSender implements SmsSender {

    private final DefaultMessageService service;
    private final String senderNumber;

    public SolapiSmsSender(SmsProperties props) {
        if (isBlank(props.apiKey()) || isBlank(props.apiSecret()) || isBlank(props.senderNumber())) {
            throw new IllegalStateException("SOLAPI_ENABLED=true 이면 SOLAPI_API_KEY, SOLAPI_API_SECRET, SOLAPI_SENDER_NUMBER 가 필요합니다.");
        }
        this.service = SolapiClient.INSTANCE.createInstance(props.apiKey(), props.apiSecret());
        this.senderNumber = props.senderNumber().replace("-", "");
    }

    @Override
    public void send(String to, String text) {
        if (PhoneNumbers.isDummy(to)) {
            log.info("더미 번호라 문자를 보내지 않습니다. to={}", PhoneNumbers.mask(to));
            return;
        }
        Message message = new Message();
        message.setFrom(senderNumber);
        message.setTo(to);
        message.setText(text);
        try {
            service.send(message);
            log.info("문자 발송 요청 to={}", PhoneNumbers.mask(to));
        } catch (SolapiMessageNotReceivedException | SolapiEmptyResponseException | SolapiUnknownException e) {
            // 화면·응답에는 종류만 내보내고, 원인은 서버 로그에만 남긴다(인증번호로 보이는 6자리 숫자는 가림)
            log.warn("솔라피 발송 실패: {} - {}", e.getClass().getSimpleName(), describe(e));
            throw new SmsSendException("문자 발송 실패: " + e.getClass().getSimpleName());
        }
    }

    private static String describe(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String text = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return text.replaceAll("\\d{6}", "******");
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
