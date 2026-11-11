-- 이체와 계좌 동의. 실제 오픈뱅킹의 두 이체 방식을 모두 담는다.
--  출금이체(WITHDRAW): 고객 계좌 -> 이용기관 약정계좌.  고객 토큰(scope transfer)으로 호출
--  입금이체(DEPOSIT) : 이용기관 약정계좌 -> 수취 계좌(여러 건 가능). 이용기관 토큰(scope oob)으로 호출

-- 이용기관의 약정계좌와 입금이체용 암호문구(해시)
ALTER TABLE oauth_client
    ADD COLUMN escrow_account_id          BIGINT       NULL COMMENT '약정계좌(이용기관 소유 bank_account)',
    ADD COLUMN transfer_pass_phrase_hash  VARCHAR(100) NULL COMMENT '입금이체 암호문구 해시',
    ADD CONSTRAINT fk_oauth_client_escrow_account FOREIGN KEY (escrow_account_id) REFERENCES bank_account (id);

-- 고객이 인증 화면에서 이용기관에 허용한 계좌. 허용하지 않은 계좌는 조회도 이체도 할 수 없다.
CREATE TABLE account_consent (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    client_pk      BIGINT      NOT NULL,
    account_id     BIGINT      NOT NULL,
    inquiry_agree  TINYINT(1)  NOT NULL DEFAULT 0,
    transfer_agree TINYINT(1)  NOT NULL DEFAULT 0,
    agreed_at      DATETIME(6) NOT NULL,
    revoked_at     DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_consent UNIQUE (client_pk, account_id),
    INDEX idx_account_consent_account (account_id),
    CONSTRAINT fk_account_consent_client FOREIGN KEY (client_pk) REFERENCES oauth_client (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_consent_account FOREIGN KEY (account_id) REFERENCES bank_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 이체 요청 한 건(거래고유번호 하나). 입금이체는 여러 항목을 한 번에 보낼 수 있다.
CREATE TABLE bank_transfer (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    client_pk    BIGINT      NOT NULL,
    bank_tran_id VARCHAR(20) NOT NULL,
    kind         VARCHAR(10) NOT NULL COMMENT 'WITHDRAW 출금이체, DEPOSIT 입금이체',
    customer_id  BIGINT      NULL COMMENT '출금이체를 요청한 고객',
    item_count   INT         NOT NULL,
    total_amt    BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL COMMENT 'COMPLETED 전체 성공, PARTIAL 일부 성공, FAILED 전체 실패',
    rsp_code     CHAR(5)     NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bank_transfer_tran UNIQUE (client_pk, bank_tran_id),
    INDEX idx_bank_transfer_created (created_at),
    CONSTRAINT fk_bank_transfer_client FOREIGN KEY (client_pk) REFERENCES oauth_client (id) ON DELETE CASCADE,
    CONSTRAINT ck_bank_transfer_count CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT ck_bank_transfer_total CHECK (total_amt > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE bank_transfer_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    transfer_id     BIGINT       NOT NULL,
    tran_no         INT          NOT NULL COMMENT '요청 안에서의 순번(1부터)',
    from_account_id BIGINT       NULL,
    to_account_id   BIGINT       NULL COMMENT '수취 계좌를 찾지 못하면 NULL',
    tran_amt        BIGINT       NOT NULL,
    print_content   VARCHAR(100) NULL COMMENT '수취인 통장에 찍힐 문구',
    status          VARCHAR(20)  NOT NULL COMMENT 'COMPLETED 또는 FAILED',
    rsp_code        CHAR(5)      NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bank_transfer_item UNIQUE (transfer_id, tran_no),
    INDEX idx_bank_transfer_item_from (from_account_id),
    INDEX idx_bank_transfer_item_to (to_account_id),
    CONSTRAINT fk_bank_transfer_item_transfer FOREIGN KEY (transfer_id) REFERENCES bank_transfer (id) ON DELETE CASCADE,
    CONSTRAINT fk_bank_transfer_item_from FOREIGN KEY (from_account_id) REFERENCES bank_account (id),
    CONSTRAINT fk_bank_transfer_item_to FOREIGN KEY (to_account_id) REFERENCES bank_account (id),
    CONSTRAINT ck_bank_transfer_item_amt CHECK (tran_amt > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 거래내역이 어느 이체 항목에서 생겼는지 추적한다.
ALTER TABLE bank_transaction
    ADD COLUMN transfer_item_id BIGINT NULL AFTER bank_tran_id,
    ADD INDEX idx_bank_transaction_item (transfer_item_id),
    ADD CONSTRAINT fk_bank_transaction_item FOREIGN KEY (transfer_item_id) REFERENCES bank_transfer_item (id);
