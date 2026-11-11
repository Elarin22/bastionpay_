-- 회원
CREATE TABLE member (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(254) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    nickname      VARCHAR(30)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_email UNIQUE (email),
    CONSTRAINT uk_member_nickname UNIQUE (nickname)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 회원가입 진행 상태 (이메일 인증 → 이름/닉네임 → 비밀번호 → 가입 완료)
-- 브라우저에는 무작위 토큰만 쿠키로 주고, DB에는 토큰의 SHA-256 해시만 저장한다.
CREATE TABLE signup_request (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    token_hash            CHAR(64)     NOT NULL,
    email                 VARCHAR(254) NOT NULL,
    code_hash             CHAR(64)     NOT NULL,
    code_expires_at       DATETIME(6)  NOT NULL,
    code_attempts         INT          NOT NULL DEFAULT 0,
    last_sent_at          DATETIME(6)  NOT NULL,
    email_verified_at     DATETIME(6)  NULL,
    name                  VARCHAR(50)  NULL,
    nickname              VARCHAR(30)  NULL,
    password_hash         VARCHAR(100) NULL,
    password_confirmed_at DATETIME(6)  NULL,
    expires_at            DATETIME(6)  NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_signup_request_token UNIQUE (token_hash),
    INDEX idx_signup_request_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
