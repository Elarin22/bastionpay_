-- 로그인 실패 누적과 임시 잠금. 합성 테스트 계정(설정의 allowlist)에만 행이 생긴다.
--   failed_count      : 시간창 안에서 센 연속 실패 횟수
--   window_started_at : 이번 시간창이 시작된 시각 (UTC)
--   locked_until      : 이 시각(UTC)까지 잠김. NULL 이면 잠기지 않음
CREATE TABLE account_lockout (
    member_id         BIGINT      NOT NULL,
    failed_count      INT         NOT NULL DEFAULT 0,
    window_started_at DATETIME(6) NULL,
    locked_at         DATETIME(6) NULL,
    locked_until      DATETIME(6) NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id),
    INDEX idx_account_lockout_locked_until (locked_until),
    CONSTRAINT fk_account_lockout_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 감사 기록. 누가(SYSTEM 자동 / ADMIN 관리자) 어떤 계정에 무엇을 했는지 남긴다. 한 번 쓰면 고치지 않는다.
-- security_event 와 같은 event_id 로 이어져서, 보안 이벤트에서 감사 기록을, 감사 기록에서 이벤트를 찾을 수 있다.
CREATE TABLE audit_log (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    occurred_at      DATETIME(6) NOT NULL COMMENT 'UTC',
    actor_type       VARCHAR(10) NOT NULL COMMENT 'SYSTEM, ADMIN',
    actor_member_id  BIGINT      NULL,
    action           VARCHAR(40) NOT NULL COMMENT 'ACCOUNT_LOCKED, ACCOUNT_UNLOCKED',
    target_member_id BIGINT      NULL,
    reason_code      VARCHAR(40) NULL,
    source_ip        VARCHAR(45) NULL,
    trace_id         VARCHAR(40) NULL,
    event_id         CHAR(36)    NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_log_time (occurred_at),
    INDEX idx_audit_log_target (target_member_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
