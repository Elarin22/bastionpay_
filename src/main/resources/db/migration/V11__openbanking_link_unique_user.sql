-- 한 은행 고객(user_seq_no)은 한 회원에게만 연결한다. 이미 같은 고객이 여러 회원에 연결돼 있으면
-- 가장 먼저 연결한 회원만 남기고 나머지 연결은 지운다(다시 연결하면 된다).
DELETE l FROM openbanking_link l
JOIN openbanking_link keep ON keep.user_seq_no = l.user_seq_no AND keep.id < l.id;

ALTER TABLE openbanking_link ADD CONSTRAINT uk_openbanking_link_user UNIQUE (user_seq_no);
