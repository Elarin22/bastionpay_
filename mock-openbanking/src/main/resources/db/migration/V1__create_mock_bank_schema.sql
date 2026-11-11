-- 가짜 오픈뱅킹 스키마. 금액은 원 단위 정수(BIGINT)로 저장한다.
-- 비밀값(client secret, 인증 코드, 토큰)은 원문을 저장하지 않고 해시만 저장한다.

-- 이용기관(BastionPay 같은 앱). client_id/secret 으로 인증하고, 등록된 redirect URI 로만 돌려보낸다.
CREATE TABLE oauth_client (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    client_id          VARCHAR(40)  NOT NULL,
    client_secret_hash VARCHAR(100) NOT NULL,
    name               VARCHAR(100) NOT NULL,
    org_code           CHAR(10)     NOT NULL COMMENT '이용기관코드. 거래고유번호의 앞 10자리',
    enabled            TINYINT(1)   NOT NULL DEFAULT 1,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_oauth_client_client_id UNIQUE (client_id),
    CONSTRAINT uk_oauth_client_org_code UNIQUE (org_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE oauth_client_redirect_uri (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    client_pk    BIGINT       NOT NULL,
    redirect_uri VARCHAR(300) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_redirect_uri UNIQUE (client_pk, redirect_uri),
    CONSTRAINT fk_redirect_uri_client FOREIGN KEY (client_pk) REFERENCES oauth_client (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 은행 고객. 인증 화면에서 로그인하는 사람이다.
CREATE TABLE bank_customer (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_seq_no   CHAR(10)     NOT NULL COMMENT '오픈뱅킹 사용자일련번호',
    login_id      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bank_customer_user_seq_no UNIQUE (user_seq_no),
    CONSTRAINT uk_bank_customer_login_id UNIQUE (login_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE bank_account (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id         BIGINT       NOT NULL,
    fintech_use_num     CHAR(24)     NOT NULL COMMENT '핀테크이용번호',
    bank_code_std       CHAR(3)      NOT NULL,
    bank_name           VARCHAR(30)  NOT NULL,
    account_num         VARCHAR(20)  NOT NULL,
    account_num_masked  VARCHAR(20)  NOT NULL,
    account_alias       VARCHAR(50)  NOT NULL,
    account_holder_name VARCHAR(50)  NOT NULL,
    account_type        CHAR(1)      NOT NULL DEFAULT '1' COMMENT '1 수시입출금, 2 예적금',
    balance_amt         BIGINT       NOT NULL DEFAULT 0,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT '잔액 동시 변경 방지(낙관적 락)',
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bank_account_fintech_use_num UNIQUE (fintech_use_num),
    CONSTRAINT uk_bank_account_number UNIQUE (bank_code_std, account_num),
    INDEX idx_bank_account_customer (customer_id),
    CONSTRAINT fk_bank_account_customer FOREIGN KEY (customer_id) REFERENCES bank_customer (id),
    CONSTRAINT ck_bank_account_balance CHECK (balance_amt >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE bank_transaction (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    account_id        BIGINT       NOT NULL,
    tran_at           DATETIME(6)  NOT NULL,
    inout_type        VARCHAR(10)  NOT NULL COMMENT 'IN 입금, OUT 출금',
    tran_type         VARCHAR(20)  NOT NULL COMMENT '거래 구분(이체, 급여 등)',
    print_content     VARCHAR(100) NOT NULL,
    tran_amt          BIGINT       NOT NULL,
    after_balance_amt BIGINT       NOT NULL,
    branch_name       VARCHAR(50)  NULL,
    bank_tran_id      VARCHAR(20)  NULL COMMENT '이 거래를 만든 API 요청의 거래고유번호',
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_bank_transaction_account_time (account_id, tran_at),
    CONSTRAINT fk_bank_transaction_account FOREIGN KEY (account_id) REFERENCES bank_account (id),
    CONSTRAINT ck_bank_transaction_amt CHECK (tran_amt > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 인증 코드. 한 번만 쓸 수 있고 금방 만료된다.
CREATE TABLE authorization_code (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code_hash    CHAR(64)     NOT NULL,
    client_pk    BIGINT       NOT NULL,
    customer_id  BIGINT       NOT NULL,
    redirect_uri VARCHAR(300) NOT NULL,
    scope        VARCHAR(100) NOT NULL,
    expires_at   DATETIME(6)  NOT NULL,
    used_at      DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_authorization_code_hash UNIQUE (code_hash),
    INDEX idx_authorization_code_expires (expires_at),
    CONSTRAINT fk_authorization_code_client FOREIGN KEY (client_pk) REFERENCES oauth_client (id) ON DELETE CASCADE,
    CONSTRAINT fk_authorization_code_customer FOREIGN KEY (customer_id) REFERENCES bank_customer (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- access token. customer_id 가 NULL 이면 이용기관 토큰(2-legged, 이체 등)이다.
CREATE TABLE access_token (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    token_hash  CHAR(64)     NOT NULL,
    client_pk   BIGINT       NOT NULL,
    customer_id BIGINT       NULL,
    scope       VARCHAR(100) NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    revoked_at  DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_access_token_hash UNIQUE (token_hash),
    INDEX idx_access_token_expires (expires_at),
    CONSTRAINT fk_access_token_client FOREIGN KEY (client_pk) REFERENCES oauth_client (id) ON DELETE CASCADE,
    CONSTRAINT fk_access_token_customer FOREIGN KEY (customer_id) REFERENCES bank_customer (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- API 호출 기록. (이용기관, 거래고유번호)가 유일해서 같은 번호로 다시 호출하면 중복으로 거절할 수 있다.
CREATE TABLE api_call_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    client_pk    BIGINT      NOT NULL,
    api_name     VARCHAR(50) NOT NULL,
    bank_tran_id VARCHAR(20) NOT NULL,
    customer_id  BIGINT      NULL,
    rsp_code     CHAR(5)     NOT NULL,
    called_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_api_call_log_tran UNIQUE (client_pk, bank_tran_id),
    INDEX idx_api_call_log_called (called_at),
    CONSTRAINT fk_api_call_log_client FOREIGN KEY (client_pk) REFERENCES oauth_client (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
