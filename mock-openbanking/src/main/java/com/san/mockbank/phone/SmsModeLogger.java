package com.san.mockbank.phone;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 시작할 때 문자 발송 방식을 한 줄로 알려 준다(.env 설정이 읽혔는지 확인하는 용도). 키 값은 출력하지 않는다. */
@Slf4j
@Component
public class SmsModeLogger implements ApplicationRunner {

    private final SmsProperties props;

    public SmsModeLogger(SmsProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.enabled()) {
            log.info("문자 발송 방식: 솔라피(실제 발송). 더미 번호(010-0000-xxxx)에는 보내지 않습니다.");
        } else if (props.apiKey() != null && !props.apiKey().isBlank()) {
            log.warn("문자 발송 방식: 콘솔(실제 발송 안 함). 솔라피 키는 읽었지만 SOLAPI_ENABLED 가 true 가 아닙니다.");
        } else {
            log.info("문자 발송 방식: 콘솔(실제 발송 안 함). 실제로 보내려면 mock-openbanking/.env 에 SOLAPI_ENABLED=true 와 솔라피 키를 넣고 다시 시작하세요.");
        }
    }
}
