# mock-openbanking

금융결제원 오픈뱅킹을 흉내 내는 **교육용 가짜 서버**(포트 8090). 실제 금융 거래는 일어나지 않는다.
BastionPay 는 이 서버를 `OPENBANKING_BASE_URL` 만 바꿔 실제 오픈뱅킹처럼 호출한다.

## 실행

1. `mockbank-mysql` 폴더에서 `docker compose up -d` (MySQL, 포트 3307)
2. `.env.example` 을 `.env` 로 복사해 DB 비밀번호 입력
3. `sh gradlew bootRun` → Flyway 가 테이블을 만든다
4. 더미 데이터: `db/seed/README.md` 참고 (`db/seed/mockbank.sql`)

BastionPay 쪽 `.env`: `OPENBANKING_BASE_URL=http://localhost:8090`, `OPENBANKING_CLIENT_ID`, `OPENBANKING_CLIENT_SECRET`,
`OPENBANKING_ORG_CODE` (값은 `db/seed/README.md` 의 개발용 공개 값)

## 제공하는 API

| 경로 | 설명 |
|---|---|
| `GET /oauth/2.0/authorize` | 사용자 인증 화면(휴대폰 번호 입력 → 문자 인증번호 확인 → 계좌 선택·동의 → redirect URI 로 코드 전달) |
| `POST /oauth/2.0/token` | `authorization_code`(사용자 토큰), `client_credentials`(이용기관 토큰, scope `oob`) |
| `GET /v2.0/account/list` | 동의받은 계좌 목록. scope `login` 또는 `inquiry` |
| `GET /v2.0/account/balance/fin_num` | 잔액조회. scope `inquiry`. 거래고유번호 = 이용기관코드 + `U` + 영대문자·숫자 9자리, 재사용 불가 |
| `GET /v2.0/account/transaction_list/fin_num` | 거래내역조회. scope `inquiry`. `inquiry_type` A/I/O(전체/입금/출금), `from_date`~`to_date`(yyyyMMdd, 양끝 포함, 최대 92일), `sort_order` D/A, `page`(1부터)·`page_size`(최대 100). 거래고유번호 규칙은 잔액조회와 같다 |

| `POST /v2.0/transfer/withdraw/fin_num` | 출금이체: 고객 계좌 → 이용기관 약정계좌. 사용자 토큰(scope `transfer`) + 그 계좌의 이체 동의 필요. JSON 본문 `bank_tran_id`, `tran_dtime`, `fintech_use_num`, `tran_amt`(문자열 정수), `print_content`(20자) |
| `POST /v2.0/transfer/deposit/fin_num` | 입금이체: 약정계좌 → 수취 계좌 최대 100건. 이용기관 토큰(`client_credentials`, scope `oob`) + 입금이체 암호문구(`wd_pass_phrase`). 항목별 성공·실패(부분 성공 `A0007`, 전부 실패 `A0012`). 수취인 이름은 마스킹해 응답 |


## 보안 규칙(테스트로 확인)

- redirect URI 는 등록된 값과 **글자 하나까지** 같아야 한다. 다르면 코드를 어디로도 보내지 않고 오류 화면만 보여 준다.
- 인증 코드는 5분, 한 번만 사용. client secret·코드·토큰은 DB 에 해시만 저장한다.
- 동의하지 않은 계좌, 남의 계좌, 없는 계좌는 같은 오류(`A0005`)로 응답해 존재 여부를 숨긴다.
- 휴대폰 문자 인증: 인증번호는 6자리 난수(해시만 저장), 3분 안에 입력, 틀리면 5회까지(5회째에 인증 종료), 같은 번호 재발송은 60초 간격·시간당 5회.
  새로 받으면 이전 인증번호는 못 쓰고, 인증번호는 받은 브라우저 세션에서만 쓸 수 있다. 등록되지 않은 번호에도 똑같이 "보냈다"고 응답한다.
- 문자는 기본으로 보내지 않는다(개발용으로 화면·서버 로그에 인증번호 표시). `SOLAPI_ENABLED=true` 와 솔라피 키·발신번호(`.env.example` 참고)를 넣으면
  솔라피(`com.solapi:sdk`)로 실제 발송한다. 더미 번호(010-0000-xxxx)에는 보내지 않는다. 실제 문자를 받아 보려면 `db/seed/mockbank.local.sql` 의 번호를 본인 번호로 바꾼다.
- 이체는 한 요청이 한 DB 트랜잭션이다. 이체에 쓰는 계좌는 항상 id 순서로 행 잠금(`FOR UPDATE`)을 걸어, 같은 계좌에서 동시에 출금해도
  잔액이 음수가 되거나 이중으로 나가지 않고 서로 반대 방향 이체가 동시에 들어와도 교착이 생기지 않는다(동시성 테스트로 확인).
- 검증은 잔액을 바꾸기 전에 모두 끝낸다. 실패한 이체는 잔액·거래내역에 흔적을 남기지 않는다. 같은 거래고유번호는 한 번만 처리한다(동시에 와도 한 번).
- 한 건 최대 금액(`mockbank.transfer-max-amount`, 기본 1천만 원)과 잔액은 서버가 정한다. 금액은 1 이상의 정수 문자열만 받는다.
- 이용기관 토큰으로 사용자 계좌에서 출금하거나, 사용자 토큰으로 입금이체를 호출할 수 없다.
- 응답 코드(`A0000` 등)는 이 서버가 임의로 정한 값이다. 금융결제원의 실제 코드표가 아니다.
