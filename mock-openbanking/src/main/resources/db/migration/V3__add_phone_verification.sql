-- 오픈뱅킹 사용자 인증을 휴대폰 문자 인증으로 한다. 고객은 등록된 휴대폰 번호로 본인임을 확인한다.
ALTER TABLE bank_customer
    ADD COLUMN phone VARCHAR(11) NULL COMMENT '휴대폰 번호(숫자만)',
    ADD CONSTRAINT uk_bank_customer_phone UNIQUE (phone);

-- 인증번호. 원문은 저장하지 않고 salt 를 섞은 SHA-256 해시만 저장한다.
-- 한 번 발급한 인증번호는 유효 시간·시도 횟수가 정해져 있고, 새로 발급하면 이전 것은 쓸 수 없다.
CREATE TABLE sms_verification (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    phone       VARCHAR(11) NOT NULL,
    code_salt   CHAR(32)    NOT NULL,
    code_hash   CHAR(64)    NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    consumed_at DATETIME(6) NULL COMMENT '인증에 성공했거나 새 인증번호로 대체된 시각',
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_sms_verification_phone_created (phone, created_at),
    INDEX idx_sms_verification_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
