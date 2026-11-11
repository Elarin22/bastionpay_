-- 이체 요청. 출금이체(내 계좌 -> BastionPay 약정계좌)와 입금이체(약정계좌 -> 받는 계좌)를 이어서 처리하는 동안의 상태를 남긴다.
-- (member_id, request_id) 가 유일해서 같은 요청(더블클릭·새로고침·재전송)이 두 번 실행되지 않는다.
-- 받는 계좌번호 원문은 저장하지 않는다(마스킹한 값만). 출금 계좌는 핀테크이용번호로 가리킨다.
CREATE TABLE transfer_request (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    member_id            BIGINT       NOT NULL,
    request_id           CHAR(36)     NOT NULL,
    from_fintech_use_num CHAR(24)     NOT NULL,
    from_label           VARCHAR(100) NOT NULL,
    to_bank_code         CHAR(3)      NOT NULL,
    to_account_masked    VARCHAR(30)  NOT NULL,
    to_holder_masked     VARCHAR(50)  NULL,
    amount               BIGINT       NOT NULL,
    memo                 VARCHAR(20)  NULL,
    status               VARCHAR(20)  NOT NULL COMMENT 'PENDING, WITHDRAWN, COMPLETED, FAILED, REFUNDED, NEEDS_REVIEW',
    failure_code         VARCHAR(30)  NULL,
    withdraw_tran_id     VARCHAR(20)  NULL,
    deposit_tran_id      VARCHAR(20)  NULL,
    refund_tran_id       VARCHAR(20)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_transfer_request UNIQUE (member_id, request_id),
    INDEX idx_transfer_request_status (status),
    CONSTRAINT fk_transfer_request_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_transfer_request_amount CHECK (amount BETWEEN 1 AND 10000000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
