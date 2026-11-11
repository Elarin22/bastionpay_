-- 관리자 보안 대응 기능: 인시던트 티켓, IP 차단, 계정 수동 잠금.

-- 계정 수동 잠금(관리자·자동화): member.status 가 LOCKED 이면 로그인할 수 없다. 사유와 시각을 남긴다(시각은 UTC).
ALTER TABLE member
    ADD COLUMN locked_at  DATETIME(6)  NULL AFTER status,
    ADD COLUMN lock_reason VARCHAR(200) NULL AFTER locked_at;

-- 인시던트 티켓. 같은 출발지 IP 의 '열린' 티켓은 하나만 둔다(open_flag 가 열림일 때만 1 이라, 닫힌 티켓은 여러 개여도 된다).
-- summary 는 그 IP 의 보안 이벤트를 자동으로 모은 사람이 읽는 요약이다.
CREATE TABLE incident (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(200) NOT NULL,
    src_ip      VARCHAR(45)  NOT NULL,
    severity    VARCHAR(10)  NOT NULL COMMENT 'LOW, MEDIUM, HIGH, CRITICAL',
    status      VARCHAR(10)  NOT NULL COMMENT 'OPEN, CLOSED',
    summary     TEXT         NULL,
    event_count INT          NOT NULL DEFAULT 0,
    actions     VARCHAR(255) NULL COMMENT '그 IP 에 이미 취해진 조치(이벤트 종류)',
    created_by  VARCHAR(50)  NOT NULL COMMENT '관리자 닉네임 또는 api',
    closed_by   VARCHAR(50)  NULL,
    created_at  DATETIME(6)  NOT NULL COMMENT 'UTC',
    updated_at  DATETIME(6)  NOT NULL COMMENT 'UTC',
    closed_at   DATETIME(6)  NULL COMMENT 'UTC',
    open_flag   TINYINT GENERATED ALWAYS AS (IF(status = 'OPEN', 1, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_incident_open_per_ip UNIQUE (src_ip, open_flag),
    INDEX idx_incident_status_updated (status, updated_at),
    INDEX idx_incident_src_ip_closed (src_ip, closed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 차단된 IP. 이 표에 있는 IP 의 요청은 앱에 닿기 전에 403 으로 돌려보낸다.
CREATE TABLE blocked_ip (
    ip         VARCHAR(45)  NOT NULL,
    reason     VARCHAR(200) NULL,
    blocked_by VARCHAR(50)  NOT NULL COMMENT '관리자 닉네임 또는 api',
    blocked_at DATETIME(6)  NOT NULL COMMENT 'UTC',
    PRIMARY KEY (ip),
    INDEX idx_blocked_ip_time (blocked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
