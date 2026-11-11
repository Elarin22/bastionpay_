-- 보안 이벤트. 로그 파일(JSONL)과 같은 내용을 보안 대시보드가 집계할 수 있게 DB 에도 남긴다.
-- occurred_at 은 UTC. 해당되는 필드만 채우고 나머지는 NULL 이다(없는 값을 만들어 넣지 않는다).
-- 비밀번호·토큰·쿠키·이메일 원문·계좌번호 원문은 이 테이블에 들어가지 않는다.
--   login_subject_hash : 로그인에 쓴 이메일을 서버 비밀키로 HMAC-SHA256 한 값(계정을 식별하되 원문은 숨긴다)
--   account_ref        : 가상 계좌 식별자(원문 계좌번호가 아닌 마스킹한 값)
CREATE TABLE security_event (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    event_id           CHAR(36)     NOT NULL,
    event_type         VARCHAR(40)  NOT NULL,
    occurred_at        DATETIME(6)  NOT NULL COMMENT 'UTC',
    trace_id           VARCHAR(40)  NULL,
    source_ip          VARCHAR(45)  NULL,
    member_id          BIGINT       NULL,
    login_subject_hash CHAR(64)     NULL,
    account_ref        VARCHAR(40)  NULL,
    transfer_id        BIGINT       NULL,
    amount             BIGINT       NULL,
    currency           CHAR(3)      NULL,
    severity           VARCHAR(10)  NOT NULL COMMENT 'INFO, LOW, MEDIUM, HIGH, CRITICAL',
    outcome            VARCHAR(10)  NOT NULL COMMENT 'SUCCESS, FAILURE, DENIED, REVIEW',
    reason_code        VARCHAR(40)  NULL,
    http_method        VARCHAR(10)  NULL COMMENT '요청이 막힌 이벤트(인증 필요·권한 없음·CSRF)에만',
    request_path       VARCHAR(200) NULL COMMENT '쿼리 스트링 없음, 긴 숫자는 가림',
    PRIMARY KEY (id),
    CONSTRAINT uk_security_event_event_id UNIQUE (event_id),
    INDEX idx_security_event_time (occurred_at),
    INDEX idx_security_event_type_time (event_type, occurred_at),
    INDEX idx_security_event_ip_time (source_ip, occurred_at),
    INDEX idx_security_event_subject_time (login_subject_hash, occurred_at),
    INDEX idx_security_event_member_time (member_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
