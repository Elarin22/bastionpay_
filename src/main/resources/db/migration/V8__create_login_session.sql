-- 로그인 세션. "마지막 활동 후 일정 시간(기본 30분)이 지나면 다시 로그인"을 서버가 판정하기 위한 기록이다.
-- session_id 는 그 로그인의 Refresh Token family_id 와 같다. Access Token 의 sid 클레임이 이 값을 가리킨다.
-- 모든 시각은 UTC. 로그아웃·만료되면 ended_at 이 채워지고, 그 세션의 토큰은 남은 시간과 상관없이 더는 쓸 수 없다.
CREATE TABLE login_session (
    session_id       CHAR(36)    NOT NULL,
    member_id        BIGINT      NOT NULL,
    idle_expires_at  DATETIME(6) NOT NULL COMMENT '이 시각까지 활동이 없으면 만료. 활동할 때마다 뒤로 밀린다',
    last_activity_at DATETIME(6) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    ended_at         DATETIME(6) NULL,
    end_reason       VARCHAR(20) NULL COMMENT 'LOGOUT, IDLE_TIMEOUT, REVOKED',
    PRIMARY KEY (session_id),
    INDEX idx_login_session_member (member_id),
    INDEX idx_login_session_idle (idle_expires_at),
    CONSTRAINT fk_login_session_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
