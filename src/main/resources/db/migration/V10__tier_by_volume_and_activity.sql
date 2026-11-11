-- 등급(tier)을 누적 이체 금액으로 자동 승급하고, 오래 이용하지 않으면 자동 강등하기 위한 기록. 시각은 서버 지역 시간(member·transfer_request 와 같은 기준).
--   last_active_at   : 마지막으로 앱을 이용한 시각(로그인·화면 이용). 이것이 오래되면 한 단계씩 강등한다
--   tier_changed_at  : 등급이 마지막으로 바뀐 시각(승급·강등). 강등 기간은 이 시각과 마지막 이용 중 늦은 쪽부터 센다
--   tier_baseline_at : 누적 이체 금액을 세기 시작하는 시각. 가입 시각에서 시작하고, 강등되면 그 시각으로 다시 시작한다
ALTER TABLE member
    ADD COLUMN last_active_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER lock_reason,
    ADD COLUMN tier_changed_at  DATETIME(6) NULL AFTER last_active_at,
    ADD COLUMN tier_baseline_at DATETIME(6) NULL AFTER tier_changed_at;

-- 기존 회원: 마지막 이용은 지금으로 두고(마이그레이션 직후 한꺼번에 강등되지 않게), 누적은 가입 시점부터 센다
UPDATE member SET tier_baseline_at = created_at;
ALTER TABLE member MODIFY COLUMN tier_baseline_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- 이체 한도는 이제 등급마다 다르다(STANDARD 200만원, VIP 1,000만원, VVIP 제한 없음). 금액의 위쪽 한계는 앱이 등급으로 판단한다
ALTER TABLE transfer_request DROP CHECK ck_transfer_request_amount;
ALTER TABLE transfer_request ADD CONSTRAINT ck_transfer_request_amount CHECK (amount >= 1);
