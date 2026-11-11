-- 오픈뱅킹 연결. access token 은 애플리케이션에서 AES-GCM 으로 암호화한 값만 저장한다.
CREATE TABLE openbanking_link (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL,
    user_seq_no      VARCHAR(20)  NOT NULL,
    access_token_enc VARCHAR(2048) NOT NULL,
    scope            VARCHAR(100) NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_openbanking_link_member UNIQUE (member_id),
    CONSTRAINT fk_openbanking_link_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 인증 시작 때 만든 state. 원문은 저장하지 않고 SHA-256 해시만 저장하며, 한 번 쓰면 지운다.
-- 콜백은 다른 사이트에서 넘어오는 요청이라 SameSite=Strict 로그인 쿠키가 실리지 않는다.
-- 그래서 state 로 어느 회원의 요청인지 서버에서 찾는다.
CREATE TABLE openbanking_auth_state (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    state_hash CHAR(64)    NOT NULL,
    member_id  BIGINT      NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_openbanking_auth_state_hash UNIQUE (state_hash),
    INDEX idx_openbanking_auth_state_expires (expires_at),
    CONSTRAINT fk_openbanking_auth_state_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
