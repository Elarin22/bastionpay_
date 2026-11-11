package com.san.mockbank.phone;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 개발용: 문자를 보내지 않고 서버 로그에 남긴다. 번호는 가려서 남긴다. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mockbank.sms", name = "enabled", havingValue = "false", matchIfMissing = true)
public class ConsoleSmsSender implements SmsSender {

    @Override
    public void send(String to, String text) {
        log.info("[개발용 문자] to={} text={}", PhoneNumbers.mask(to), text);
    }

    @Override
    public boolean revealsCode() {
        return true;
    }
}
