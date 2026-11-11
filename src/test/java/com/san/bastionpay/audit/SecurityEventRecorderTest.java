package com.san.bastionpay.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 한 줄 JSON 로그와 DB 저장이 같은 내용인지 확인한다. 로그는 파일 대신 테스트용 어펜더로 받는다. */
@SpringBootTest(properties = "app.security.event-db-enabled=true")
class SecurityEventRecorderTest {

    @Autowired SecurityEventRecorder recorder;
    @Autowired SecurityEventRepository repository;

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private Logger eventLogger;
    private String eventId;

    @BeforeEach
    void attach() {
        eventLogger = (Logger) LoggerFactory.getLogger(SecurityEventRecorder.LOGGER_NAME);
        lines.start();
        eventLogger.addAppender(lines);
    }

    @AfterEach
    void cleanUp() {
        eventLogger.detachAppender(lines);
        MDC.clear();
        // 이벤트는 별도 트랜잭션으로 커밋되므로 테스트가 만든 행만 지운다
        if (eventId != null) repository.findAll().stream().filter(e -> e.getEventId().equals(eventId)).forEach(repository::delete);
    }

    @Test
    void oneEventBecomesOneJsonLineAndOneRowWithRequestContext() {
        MDC.put("traceId", "trace-test-1");
        MDC.put("clientIp", "203.0.113.77");
        SecurityEvent event = SecurityEvent.of(SecurityEventType.LOGIN_FAILURE, EventOutcome.FAILURE)
                .loginSubjectHash("a".repeat(64)).reasonCode("BAD_CREDENTIALS").build();
        eventId = event.eventId();

        recorder.record(event);

        // 로그: 메시지 자체가 JSON 한 줄이고 줄바꿈이 없다
        String line = lines.list.getFirst().getFormattedMessage();
        assertThat(line).doesNotContain("\n");
        JsonNode json = JsonMapper.builder().build().readTree(line);
        assertThat(json.get("event_id").asString()).isEqualTo(eventId);
        assertThat(json.get("event_type").asString()).isEqualTo("LOGIN_FAILURE");
        assertThat(json.get("trace_id").asString()).isEqualTo("trace-test-1");       // 요청 추적 ID 가 채워진다
        assertThat(json.get("source_ip").asString()).isEqualTo("203.0.113.77");     // 출발지 IP 가 채워진다
        assertThat(json.get("login_subject_hash").asString()).hasSize(64);
        assertThat(json.has("member_id")).isFalse();                                // 없는 값은 만들지 않는다

        // DB: 같은 내용이 저장된다
        SecurityEventEntity row = repository.findAll().stream().filter(e -> e.getEventId().equals(eventId)).findFirst().orElseThrow();
        assertThat(row.getEventType()).isEqualTo(SecurityEventType.LOGIN_FAILURE);
        assertThat(row.getSourceIp()).isEqualTo("203.0.113.77");
        assertThat(row.getTraceId()).isEqualTo("trace-test-1");
        assertThat(row.getReasonCode()).isEqualTo("BAD_CREDENTIALS");
        assertThat(row.getOutcome()).isEqualTo(EventOutcome.FAILURE);
    }
}
