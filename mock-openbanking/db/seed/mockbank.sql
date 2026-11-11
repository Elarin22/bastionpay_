-- 가짜 오픈뱅킹(mock-openbanking) 개발용 더미 데이터. 팀원 모두 같은 데이터로 맞추기 위한 파일이다.
-- 대상 DB: mockbank_db (mockbank-mysql, 3307). 개발 DB 전용이며 운영에는 넣지 않는다. 가상의 은행(한빛은행 901, 새솔은행 902)이다.
-- 테스트 계정(test/vip/vvip)의 계좌 잔액은 모두 1억 원 안팎이다. BastionPay 약정계좌(id 1)는 이체 중간 계좌로 5천만 원.
-- 여러 번 실행해도 같은 결과가 되도록 고정 id 기준으로 갱신한다. 거래일은 실행 시점 기준 상대 날짜다.
--
-- 오픈뱅킹 사용자 인증은 휴대폰 문자 인증이다. 더미 고객의 휴대폰 번호(실제로는 쓰이지 않는 번호라 문자를 보내지 않고,
-- 개발 중에는 인증번호를 화면과 서버 로그에 보여 준다):
--   test@test.com 회원 = 010-0000-0002,  vip@test.com = 010-0000-0003,  vvip@test.com = 010-0000-0004
--   (010-0000-0001 은 BastionPay 약정계좌 소유자로 잠겨 있어 인증할 수 없다)
-- 이용기관 BastionPay (BastionPay 의 OPENBANKING_CLIENT_ID / CLIENT_SECRET / ORG_CODE 에 같은 값을 넣는다)
--   client_id: bastionpay-dev-client   client_secret: bastionpay-dev-secret   이용기관코드: M202610001
--   입금이체 암호문구: bastionpay-dev-pass
-- 위 값은 개발용 공개 값이다. 실제 금융결제원 키가 아니다.

INSERT INTO bank_customer (id, user_seq_no, login_id, password_hash, name, phone, status, created_at) VALUES
    (1, '1100000001', 'inst-bastionpay', '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', 'BastionPay 약정', '01000000001', 'LOCKED', NOW(6)),
    (2, '1100000002', 'test@test.com', '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', '테스터', '01000000002', 'ACTIVE', NOW(6)),
    (3, '1100000003', 'vip@test.com', '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', '브이아이피', '01000000003', 'ACTIVE', NOW(6)),
    (4, '1100000004', 'vvip@test.com', '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', '브이브이아이피', '01000000004', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE login_id = VALUES(login_id), password_hash = VALUES(password_hash), name = VALUES(name), phone = VALUES(phone), status = VALUES(status);

INSERT INTO bank_account (id, customer_id, fintech_use_num, bank_code_std, bank_name, account_num, account_num_masked,
                          account_alias, account_holder_name, account_type, balance_amt, status, version, created_at) VALUES
    (1, 1, '199900000000000000000001', '901', '한빛은행', '9001000000001', '900-***-0001', 'BastionPay 약정계좌', 'BastionPay 약정', '1', 50000000, 'ACTIVE', 0, NOW(6)),
    (2, 2, '199900000000000000000002', '901', '한빛은행', '1002345678901', '100-***-8901', '월급 통장', '테스터', '1', 102350000, 'ACTIVE', 0, NOW(6)),
    (3, 2, '199900000000000000000003', '902', '새솔은행', '1102839475610', '110-***-5610', '생활비', '테스터', '1', 100870000, 'ACTIVE', 0, NOW(6)),
    (4, 3, '199900000000000000000004', '901', '한빛은행', '2003456789012', '200-***-9012', '주거래 통장', '브이아이피', '1', 104800000, 'ACTIVE', 0, NOW(6)),
    (5, 4, '199900000000000000000005', '901', '한빛은행', '3004567890123', '300-***-0123', '자산 관리 통장', '브이브이아이피', '1', 125000000, 'ACTIVE', 0, NOW(6))
ON DUPLICATE KEY UPDATE bank_name = VALUES(bank_name), account_alias = VALUES(account_alias),
                        account_holder_name = VALUES(account_holder_name), balance_amt = VALUES(balance_amt), status = VALUES(status);

INSERT INTO bank_transaction (id, account_id, tran_at, inout_type, tran_type, print_content, tran_amt, after_balance_amt, branch_name, created_at) VALUES
    (15, 2, NOW(6) - INTERVAL 60 DAY, 'IN', '이체', '초기 입금', 100000000, 100400000, '본점', NOW(6)),
    (16, 3, NOW(6) - INTERVAL 60 DAY, 'IN', '이체', '초기 입금', 100000000, 100000000, '본점', NOW(6)),
    (17, 4, NOW(6) - INTERVAL 60 DAY, 'IN', '이체', '초기 입금', 100000000, 100500000, '본점', NOW(6)),
    (18, 5, NOW(6) - INTERVAL 60 DAY, 'IN', '이체', '초기 입금', 100000000, 122500000, '본점', NOW(6)),
    (1, 2, NOW(6) - INTERVAL 40 DAY, 'IN', '급여', '9월 급여', 3200000, 103600000, '본점', NOW(6)),
    (2, 2, NOW(6) - INTERVAL 33 DAY, 'OUT', '이체', '월세', 700000, 102900000, '본점', NOW(6)),
    (3, 2, NOW(6) - INTERVAL 21 DAY, 'OUT', '카드', '카드 대금', 480000, 102420000, '본점', NOW(6)),
    (4, 2, NOW(6) - INTERVAL 9 DAY, 'OUT', '이체', '관리비', 120000, 102300000, '본점', NOW(6)),
    (5, 2, NOW(6) - INTERVAL 3 DAY, 'IN', '이체', '용돈', 50000, 102350000, '본점', NOW(6)),
    (6, 3, NOW(6) - INTERVAL 30 DAY, 'IN', '이체', '생활비 이체', 1000000, 101000000, '본점', NOW(6)),
    (7, 3, NOW(6) - INTERVAL 12 DAY, 'OUT', '카드', '마트', 86500, 100913500, '본점', NOW(6)),
    (8, 3, NOW(6) - INTERVAL 5 DAY, 'OUT', '카드', '교통비', 43500, 100870000, '본점', NOW(6)),
    (9, 4, NOW(6) - INTERVAL 28 DAY, 'IN', '급여', '급여', 5500000, 106000000, '본점', NOW(6)),
    (10, 4, NOW(6) - INTERVAL 15 DAY, 'OUT', '이체', '적금 이체', 500000, 105500000, '본점', NOW(6)),
    (11, 4, NOW(6) - INTERVAL 7 DAY, 'OUT', '카드', '카드 대금', 700000, 104800000, '본점', NOW(6)),
    (12, 5, NOW(6) - INTERVAL 45 DAY, 'IN', '이체', '배당금', 3000000, 125500000, '본점', NOW(6)),
    (13, 5, NOW(6) - INTERVAL 20 DAY, 'OUT', '이체', '투자 이체', 2000000, 123500000, '본점', NOW(6)),
    (14, 5, NOW(6) - INTERVAL 4 DAY, 'IN', '이체', '임대 수입', 1500000, 125000000, '본점', NOW(6))
ON DUPLICATE KEY UPDATE tran_at = VALUES(tran_at), print_content = VALUES(print_content),
                        tran_amt = VALUES(tran_amt), after_balance_amt = VALUES(after_balance_amt);

INSERT INTO oauth_client (id, client_id, client_secret_hash, name, org_code, enabled, escrow_account_id, transfer_pass_phrase_hash, created_at) VALUES
    (1, 'bastionpay-dev-client', '$2y$12$1QbM0NudHOXU9zjvMIgo2.v4RkXgW8nRJplX3U95aXqSFSAdIKJ8u', 'BastionPay', 'M202610001', 1, 1, '$2y$12$RbZEuXsPtVma3I2NmPWTB.TUx1VBptZkXFhHWD.FyTc38TTqdHDpu', NOW(6))
ON DUPLICATE KEY UPDATE client_secret_hash = VALUES(client_secret_hash), name = VALUES(name), org_code = VALUES(org_code),
                        enabled = VALUES(enabled), escrow_account_id = VALUES(escrow_account_id),
                        transfer_pass_phrase_hash = VALUES(transfer_pass_phrase_hash);

INSERT INTO oauth_client_redirect_uri (id, client_pk, redirect_uri) VALUES
    (1, 1, 'https://localhost:8080/openbanking/callback')
ON DUPLICATE KEY UPDATE redirect_uri = VALUES(redirect_uri);
