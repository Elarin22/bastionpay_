-- 등급(tier)은 권한(role)과 분리한다. role: USER / ADMIN, tier: STANDARD / VIP / VVIP
-- 기존 회원은 모두 STANDARD 로 시작한다.
ALTER TABLE member
    ADD COLUMN tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD' AFTER role;
