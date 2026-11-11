-- BastionPay 개발용 더미 회원. 팀원 모두 같은 계정으로 맞추기 위한 파일이다. 개발 DB 전용이며 운영에는 넣지 않는다.
-- 대상 DB: bastionpay_db. 여러 번 실행해도 같은 결과가 되도록 이메일 기준으로 갱신한다.
-- 모든 계정의 비밀번호: 12345678A!   (bcrypt 해시만 저장)
-- 역할(role)과 등급(tier)은 별개다. USER/ADMIN 은 권한, STANDARD/VIP/VVIP 는 등급.
INSERT INTO member (email, name, nickname, password_hash, role, tier, status, created_at, updated_at) VALUES
    ('test@test.com',  '테스터',         'tester',     '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', 'USER',  'STANDARD', 'ACTIVE', NOW(6), NOW(6)),
    ('vip@test.com',   '브이아이피',     'vip_test',   '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', 'USER',  'VIP',      'ACTIVE', NOW(6), NOW(6)),
    ('vvip@test.com',  '브이브이아이피', 'vvip_test',  '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', 'USER',  'VVIP',     'ACTIVE', NOW(6), NOW(6)),
    ('admin@test.com', '관리자',         'admin_test', '$2a$12$yykgl2dDzclW5V0pj5LkXed5g58ewuqpR3ViMO56IjLbiwoM5oj/m', 'ADMIN', 'STANDARD', 'ACTIVE', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name), nickname = VALUES(nickname), password_hash = VALUES(password_hash),
    role = VALUES(role), tier = VALUES(tier), status = VALUES(status), updated_at = NOW(6);
