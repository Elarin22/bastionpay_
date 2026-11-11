# 시나리오 재현과 증거

개인 실습망과 합성 계정에서만 실행한다. 설치 후 Graylog에서 해당 이벤트 정의 하나를 활성화하고, 동일 IP의 기존 열린 티켓과 테스트 계정 상태를 확인한다. 이전 시험 로그가 남아 있으면 새 계정을 쓰거나 검색 시간창이 끝날 때까지 기다린다. Postman 환경에 `baseUrl`, `graylogUrl`, 합성 계정 정보, 테스트 IP를 채운다. 인증서 검증은 `.local/bastionpay-ca.pem`을 신뢰하도록 설정한다. 계정 초기화·복구용 컬렉션과 앱 요청 컬렉션은 `ops/postman/`에 있다.

| 시나리오 | 앱에서 발생시키는 요청 | 원본과 탐지 기대값 | n8n과 복구 |
|---|---|---|---|
| S1 반복 로그인 실패 | `05 S1`을 1회 Send. 합성 계정에 틀린 비밀번호 5회 | 같은 IP·계정의 `BAD_CREDENTIALS` 5건/10분. 앱 `ACCOUNT_LOCKED`, Wazuh 100268, Graylog S1 | 티켓 생성 또는 갱신. 관리자 화면에서 합성 계정 잠금 해제, 티켓 종료 |
| L2 다중 계정 실패 | `04 L2`를 1회 Send. 합성 계정 5개에 각각 틀린 비밀번호 1회 | 5분 이내 같은 IP의 `login_subject_hash` 고유값 5개. Graylog `card() > 4`, Wazuh 100267 상관 룰 | 검토 티켓. 계정 잠금 여부 확인, 티켓 종료. 이전 실패가 남아 있으면 계정을 초기화 |
| S3 반복 환불 | MockBank에서 모의 계좌 연결·출금 동의를 준비하고 `02 S3` 1회 Send | 모의 해지 수취 계좌에 새 요청 2건. 같은 출발지·심각도·사유의 `TRANSFER_REFUNDED` 2건/10분. Wazuh 100270 | 티켓 생성 또는 갱신. 거래 내역에서 환불을 대조하고 티켓 종료 |
| 회원 이체 급증 | `03 동일 계정` 1회 Send. 동일 회원의 새 모의 이체 요청 5건 | `TRANSFER_REQUESTED` 5건/5분. Graylog 회원 ID 집계, Wazuh 100278 | 검토 티켓. 모의 거래 내역과 요청 ID 확인 후 티켓 종료 |
| API 인증 실패 | `07 서버 API` 1회 Send 또는 아래 명령 | 틀린 키로 GET 요청 3회/5분, HTTP 401과 `API_AUTH_FAILED`. Wazuh 100272 | 운영자 알림 분기 확인. 로컬 기본값에서는 외부 알림 노드 비활성. 일반 티켓 생성 여부를 통과 기준으로 삼지 않는다 |
| K6-A 관리자 기능 접근 거부 | 일반 합성 계정 로그인 후 `/admin/security` 접근. 시크릿 창에서 비교 시험 | HTTP 403과 `ACCESS_DENIED`/`INSUFFICIENT_AUTHORITY`. 현재 Graylog 조건은 관리자 URL 한정 필터가 없어 다른 접근 거부도 포함 | 티켓과 n8n 분기를 대조. 해당 티켓 종료, 조치를 켠 실습 환경이면 시험 IP 해제 |
| K6-B 비정상 회원 등급 변경 | 팀 워크플로 시험에서는 `TIER_CHANGED` 합성 로그로 분기를 재현. 아래 로그 검증을 먼저 수행 | 정상 사유가 아닌 등급 변경. Wazuh 100285는 `NO_REASON` 조건. 사용자 role 승급을 검증하려면 별도 역할 이벤트와 변경 전후 역할이 필요 | 기본 구성의 자동 권한 회수·계정 잠금 분기는 false. 대응 확인이 필요하면 실습용 더미 계정만 별도 승인 범위에서 사용하고 테스트 후 상태를 되돌림 |

```powershell
1..3 | ForEach-Object {
    curl.exe --cacert .local/bastionpay-ca.pem -sS -o NUL -w '%{http_code}\n' `
      -H 'X-API-Key: deliberately-invalid' https://localhost:8080/api/admin/incidents
}
```

K6-B의 룰만 확인할 때 Wazuh `wazuh-logtest`에 다음 시험 JSON을 붙여 넣고 원본 서버 룰 설명과 일치하는지 본다. 합성 로그 입력은 앱에서 실제 역할이 바뀐 증거로 쓰지 않는다.

```json
{"app_name":"bastionpay","event_source":"bastionpay_security","event_type":"TIER_CHANGED","source_ip":"192.0.2.10","member_id":999999,"severity":"CRITICAL","outcome":"SUCCESS","reason_code":"NO_REASON"}
```

## 단계별 측정

1. 시작 시간을 ISO 8601 형식으로 저장하고 요청 횟수와 HTTP 결과를 적는다. `Get-Date -Format o`를 사용한다.
2. `logs/security-events.jsonl`에서 그 시간 이후의 event_type, source_ip, reason_code, trace_id를 대조한다. Graylog에서 `filebeat_app_name:bastionpay`에 이벤트 타입 조건을 붙인다. Wazuh에서 `rule.id`를 확인한다.
3. n8n Executions에서 시작 시각·ID·분기와 응답을 확인한다. HTTP 202는 수신 응답이다. 웹의 티켓 ID·생성 또는 갱신 결과, 대응 기록을 대조한다.
4. 기존 티켓 종료, 시험 계정 잠금 해제, 시험 IP 해제 후 정상 요청 결과를 기록한다. 타임스탬프의 시간대와 기준을 맞춘 뒤 수집·탐지·티켓 지연을 계산한다.

```powershell
# 시험 직전에 실행하고, 시험이 끝난 뒤 두 번째 명령을 실행
$since = Get-Date -Format o
Get-Content logs/security-events.jsonl | ForEach-Object { $_ | ConvertFrom-Json } |
  Where-Object { $_.occurred_at -and [datetimeoffset]$_.occurred_at -ge [datetimeoffset]$since } |
  Group-Object event_type, reason_code | Select-Object Count, Name
```

| 새 PC 기록 필드 | 기록할 값 |
|---|---|
| 실행 환경 | 날짜·시간대, 코드 리비전, 주소, 서비스 버전 |
| 시험 요청 | 시작·종료, 요청 수, HTTP 결과 |
| 수집 | Graylog·Wazuh 실제 건수, 기준 시각, 룰·이벤트 ID |
| 대응 | n8n 실행 ID, API 응답, 티켓 ID 또는 운영자 분기, 시간 |
| 복구 | 잠금·차단 해제, 티켓 종료, 정상 접근 결과 |
| 캡처 | 앱 원본, 탐지, n8n, 티켓과 복구. 화면의 키·토큰·개인정보를 제외하고 저장 |

## 기존 실측 기록

팀 발표 검증표에는 S1, L2, S3, K6-A, K6-B, 이체 급증 6개가 통과로, 서버 API 인증 실패가 대조 수행으로 기재됐다. 아래 수치는 기존 11월 5일 재시험 기록이며 새 PC 결과는 아직 기록하지 않았다. L2·S3의 최종 발표에서는 심희현이 시연했으며, 시험 로그와 경보 사이의 정량 지연 자료는 확보하지 못했다.

| 관찰 | 값 |
|---|---|
| S1 요청 | 09:48:12~09:49:42 KST, 5회, 첫 요청에서 마지막 요청까지 90초 |
| S1 결과 | 앱 계정 잠금, Wazuh 100268, n8n 실행 404에서 티켓 #29 생성, 실행 405에서 같은 티켓 갱신. [실행 404 캡처](evidence/S1-wazuh-ticket29.jpg)의 670ms는 n8n 내부 실행 시간이다. |
| 차단 후 재요청 | HTTP 403, 실행 406·407에서 티켓 #29 갱신. S1 시험에서 자동 차단이 과도하게 적용된 사실도 확인 |
| 차단 뒤 성공 음성 검증 | 09:54:23 검색에서 차단 뒤 `LOGIN_SUCCESS`·`TRANSFER_REQUESTED` 없음 |
| VLAN 시험 | [2026-10-27 차단·허용·원복 캡처](VLAN.md#팀-시험-기록--2026-10-27) |

## 수정 위치

Graylog 집계는 `_graylog/bastionpay-content-pack.json`의 Event Definitions, 수집 파일은 `ops/filebeat.yml`, Wazuh 필드와 빈도 조건은 `_wazuh/wazuh-config/local_rules.xml`의 100266~100285에서 확인한다. 룰 변경 후 `wazuh-logtest`로 양성·음성 로그를 먼저 대조한다. `_n8n/` JSON은 최재영의 팀 워크플로를 바탕으로 로컬 주소, 인증 재연결, 시험 초기 상태를 정리한 것이다. 자동 조치 기준을 수정할 때는 추출한 이벤트 타입과 출발지, 대상 회원 ID를 검증한 뒤 한 번에 한 분기만 시험한다.
