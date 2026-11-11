-- Refresh Token. 원문은 저장하지 않고 SHA-256 해시만 저장한다.
-- family_id: 로그인 한 번에서 이어지는 토큰 계열. 재사용이 탐지되면 계열 전체를 폐기한다.
CREATE TABLE refresh_token (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    token_hash CHAR(64)    NOT NULL,
    member_id  BIGINT      NOT NULL,
    family_id  CHAR(36)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    INDEX idx_refresh_token_family (family_id),
    INDEX idx_refresh_token_expires (expires_at),
    CONSTRAINT fk_refresh_token_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
