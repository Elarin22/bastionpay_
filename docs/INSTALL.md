# 새 Windows PC에서 BastionPay 실행

Windows 11, PowerShell 7, Java 21 JDK(`java`, `keytool`), Python 3.11+, Docker Desktop(Linux containers 및 Compose v2), Git, Filebeat 8.x, Postman Desktop을 사용한다. Docker Desktop이 실행된 상태에서 `docker info`와 `docker compose version`을 확인한다. 아래 명령은 경로에 공백이 있어도 사용할 수 있다. 실습 전용 PC 또는 격리 VM에서 작업하고 TCP 8080은 필요한 실습 클라이언트와 Docker만 접근하도록 호스트 방화벽을 제한한다.

## 1. 앱과 DB

저장소를 받거나 GitHub용 소스 ZIP을 풀고 저장소 루트에서 실행한다. `ops/init-local.py`는 이 PC에서 쓸 무작위 비밀번호·키와 SQL을 생성한다. `.env`와 `.local`은 Git 추적에서 제외한다.

```powershell
python ops/init-local.py
New-Item -ItemType Directory -Force config | Out-Null
$ks = ((Get-Content .env | Where-Object { $_ -like 'DEV_KEYSTORE_PASSWORD=*' }) -split '=', 2)[1]
keytool -genkeypair -alias bastionpay-local -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore config/local-dev.p12 -storepass $ks -dname CN=localhost -ext 'SAN=dns:localhost,dns:host.docker.internal,ip:127.0.0.1'
keytool -exportcert -rfc -alias bastionpay-local -keystore config/local-dev.p12 -storepass $ks -file .local/bastionpay-ca.pem
docker compose --env-file docker/.env -f docker/docker-compose.yml up -d
docker compose --env-file mockbank-mysql/.env -f mockbank-mysql/docker-compose.yml up -d
docker logs mysql-server --tail 20
docker logs mockbank-mysql --tail 20
```

두 MySQL 로그에 초기화 완료 및 마지막 `ready for connections`가 표시된 뒤 DB 계정을 만든다.

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -Raw .local/db-users.sql | docker exec -i mysql-server sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root bastionpay_db'
```

별도 터미널 두 개에서 앱을 실행한다. 각 프로세스가 `Started`로 표시되면 Flyway 마이그레이션이 적용됐는지 로그를 확인한다.

```powershell
# 터미널 A: 저장소 루트
.\gradlew.bat bootRun --args='--spring.profiles.active=dev'
# 터미널 B: 별도로 실행
Set-Location mock-openbanking
.\gradlew.bat bootRun --args='--server.address=127.0.0.1'
```

테스트용 데이터는 앱이 스키마를 만든 뒤 넣는다. `test-data.sql`은 과거 이벤트·티켓도 생성하므로 실측은 시험 시작 시각 이후의 이벤트만 센다. 합성 로그인과 모의 은행 값은 [시드 문서](../db/seed/README.md)에 있다. 회원가입 메일과 MockBank SMS는 dev 콘솔/화면으로 확인할 수 있다.

```powershell
# 저장소 루트에서
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -Raw db/seed/member.sql | docker exec -i mysql-server sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root bastionpay_db'
Get-Content -Raw db/seed/test-data.sql | docker exec -i mysql-server sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root bastionpay_db'
Get-Content -Raw mock-openbanking/db/seed/mockbank.sql | docker exec -i mockbank-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root mockbank_db'
Get-Content -Raw mock-openbanking/db/seed/mockbank-test.sql | docker exec -i mockbank-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root mockbank_db'
curl.exe --cacert .local/bastionpay-ca.pem -I https://localhost:8080/login
```

| 대상 | 접속 주소·포트 |
|---|---|
| BastionPay·티켓 | `https://localhost:8080` / `/admin/security/tickets` |
| MockBank | `http://localhost:8090` |
| 앱·은행 MySQL | `127.0.0.1:3306`, `127.0.0.1:3307` |
| Graylog UI·Beats | `http://localhost:9000`, `127.0.0.1:5044/TCP` |
| Wazuh Dashboard | `https://localhost:8443` |
| Wazuh Agent·등록 | `127.0.0.1:1514/TCP`, `127.0.0.1:1515/TCP` |
| n8n UI | `http://localhost:5678` |
| pfSense 관리 | 별도 격리 가상망 `https://10.10.10.1` |

## 2. Graylog, n8n, Filebeat

Docker Linux 엔진의 `vm.max_map_count`를 262144 이상으로 맞춘다. WSL2 Docker Desktop에서 확인하려면 `wsl -d docker-desktop -u root sysctl vm.max_map_count`를 사용한다. 필요할 때만 해당 엔진에서 `wsl -d docker-desktop -u root sysctl -w vm.max_map_count=262144`를 적용한다. Linux VM에서는 VM 안에서 `sudo sysctl -w vm.max_map_count=262144`를 사용한다. 로컬 인증서가 생성된 뒤 실행한다.

```powershell
docker compose --env-file .local/lab.env -f ops/observability/compose.yml config --quiet
docker compose --env-file .local/lab.env -f ops/observability/compose.yml up -d
docker compose --env-file .local/lab.env -f ops/observability/compose.yml ps
docker compose --env-file .local/lab.env -f ops/observability/compose.yml logs graylog --tail 60
```

Graylog 최초 설정 화면이 나오면 컨테이너 로그에서 bootstrap 접속 안내를 확인한다. 관리 계정은 `admin`, 로컬 암호는 `.local/lab.env`의 `GRAYLOG_ROOT_PASSWORD`를 사용한다. System → Content Packs에서 `_graylog/bastionpay-content-pack.json`을 Import 후 Install 한다. Beats Input TCP 5044 및 `BastionPay Security Events` 스트림 상태를 확인한다. 필요 시 System → Inputs에서 시작한다. n8n으로 전달하는 HTTP 알림의 예시 URL은 Docker 공용 네트워크의 `http://n8n:5678/webhook/bastionpay-graylog`다. 설치 뒤 시나리오별 Event Definitions의 스케줄을 한 개씩 켠다. Graylog 검색에서 `filebeat_app_name:bastionpay`가 실제 나타나는지 먼저 확인한다.

Filebeat 실행 파일을 내려받아 PATH에 추가하고, 저장소 루트에서 실행한다. 다른 입력 인스턴스가 같은 파일을 읽는 경우 중복 수집을 확인한다.

```powershell
$env:BASTIONPAY_LOG_PATH = Join-Path $PWD 'logs/security-events.jsonl'
$env:GRAYLOG_BEATS_ADDRESS = '127.0.0.1:5044'
filebeat.exe test config -c ops/filebeat.yml
filebeat.exe test output -c ops/filebeat.yml
filebeat.exe -e -c ops/filebeat.yml --path.data .local/filebeat
```

n8n에서 로컬 관리자 계정을 생성하고 `_n8n/BastionPay - Security Alert Intake.json`을 Import 한다. HTTP 티켓 노드의 Header Auth credential은 헤더 이름 `X-API-Key`, 값은 `.env`의 `SECURITY_API_KEY`로 새로 생성한다. Wazuh 수신 노드에는 `X-Wazuh-Webhook-Key`와 `.local/lab.env`의 해당 키를 Header Auth로 연결한다. 앱 주소 `https://host.docker.internal:8080`의 개발 인증서는 Compose에서 신뢰 목록에 연결한다. `host.docker.internal` 해석과 앱 접근을 n8n 컨테이너에서 확인한다. SSL 검증 오류는 CA·SAN·접속 주소를 대조한다. Telegram·Discord를 실습할 때만 개인 시험 채널의 credential을 연결하고 해당 노드를 켠다. API 인증 실패의 기본 판정은 `API_AUTH_FAILED?`의 운영자 분기 실행이다. 워크플로를 활성화하고 각 웹훅의 Production URL을 확인한다.

## 3. Wazuh Manager와 Agent

공식 single-node Docker 배포를 별도 폴더에서 실행한다. 이 가이드의 예시는 v4.14.10이며 기존 발표 실측 버전과 구분한다. Compose의 Dashboard 호스트 포트 `443:5601`을 `127.0.0.1:8443:5601`로 바꾸고, 1514·1515·514·55000·9200의 각 매핑 앞에도 `127.0.0.1:`을 붙여 로컬에서만 접근하도록 한다.

```powershell
git clone -b v4.14.10 --depth 1 https://github.com/wazuh/wazuh-docker.git .local/wazuh-docker
Push-Location .local/wazuh-docker/single-node
# docker-compose.yml에서 Dashboard 포트 변경 후 실행
docker compose -f generate-indexer-certs.yml run --rm generator
docker compose up -d
docker compose ps
$manager = (docker compose ps -q wazuh.manager).Trim()
Pop-Location
docker network connect bastionpay-lab $manager
```

Dashboard 로그인은 해당 공식 배포의 초기 계정 안내를 따른 뒤 교체한다. `_wazuh/wazuh-config/`의 파일을 적용한다. 컨테이너 재생성 시 `bastionpay-lab` 연결을 다시 확인한다.

```powershell
$d = '_wazuh/wazuh-config'
docker cp "$d/local_rules.xml" "${manager}:/var/ossec/etc/rules/local_rules.xml"
docker cp "$d/local_decoder.xml" "${manager}:/var/ossec/etc/decoders/local_decoder.xml"
docker cp "$d/integrations/custom-bastionpay-n8n" "${manager}:/var/ossec/integrations/custom-bastionpay-n8n"
docker cp "$d/integrations/custom-bastionpay-n8n.py" "${manager}:/var/ossec/integrations/custom-bastionpay-n8n.py"
docker exec $manager sh -c 'chown root:wazuh /var/ossec/integrations/custom-bastionpay-n8n* && chmod 750 /var/ossec/integrations/custom-bastionpay-n8n*'
docker exec -it $manager /var/ossec/bin/wazuh-logtest
```

1. `_wazuh/wazuh-config/integration.xml.example`의 키 토큰을 로컬 키로 바꿔 `.local/wazuh-docker/single-node/config/wazuh_cluster/wazuh_manager.conf`의 `<ossec_config>` 안에 추가한다. 같은 이름의 integration을 두 번 추가하지 않는다. 이 파일은 컨테이너 시작 시 적용되는 마운트 원본이다. `docker restart $manager` 후 로그를 점검한다.
2. Windows Agent를 동일 버전 공식 MSI로 설치한다. 관리자 PowerShell에서 `msiexec.exe /i <다운로드한-MSI> /qn WAZUH_MANAGER="127.0.0.1" WAZUH_REGISTRATION_SERVER="127.0.0.1"`로 설치할 수 있다. Dashboard에서 Agent 등록·Active 상태를 확인한다.
3. Dashboard의 Agents Groups에서 실습용 `bastionpay` 그룹을 만들고 Agent를 할당한다. `agent.conf.example`의 `__BASTIONPAY_ROOT__`를 이 PC의 앱 루트 절대경로로 바꾸고 그룹 `agent.conf`에 넣는다. 파일 경로가 Windows XML에 올바르게 들어갔는지 확인한다. 시험 로그와 FIM 경로는 실제 앱 실행 폴더를 따른다.
4. Dashboard에서 Agent 동기화와 경보를 확인한다. 동일 원본 로그를 `wazuh-logtest`로 검사하는 결과와, Manager가 실수신하여 Dashboard에 색인한 결과를 따로 기록한다.

파일 무결성 감시를 시연할 때는 Agent 정책을 적용하기 전에 앱 루트에 `.local/bastionpay-fim` 폴더를 만든다. 동기화 후 그 안에 시험 파일을 생성·수정·삭제하고 Wazuh 100282를 확인한다. 로컬 예시의 룰은 이 폴더 이름에 맞췄다. 원본 앱 로그 경로는 `logs/security-events.jsonl`이다.

Wazuh 경보를 Graylog에서도 검색하려면 Pack에서 만들어진 UDP 5140 Input ID를 `Wazuh 경보` 스트림 규칙의 `__LOCAL_WAZUH_INPUT_ID__`에 넣는다. 해당 Input을 실행한 뒤 Manager의 `<syslog_output>`에 공용 네트워크 주소 `graylog`, 포트 `5140`, 포맷 `json`을 지정하고 재시작한다. 먼저 Wazuh → n8n 경로를 검증한 뒤 별도 전달을 점검한다.

## 4. pfSense

[README의 VLAN 전체 설정과 재현](../README.md#vlan-전체-설정과-재현)을 따라 pfSense CE와 테스트 VM을 별도 가상망으로 설치한다. 서비스 PC에 LAN과 VPN 주소를 함께 쓰는 팀 구성의 경우 TAP과 정적 경로 설정까지 적용한다. 기존 장비의 백업은 인증서를 포함할 수 있어 새 실습망에서 새 CA와 클라이언트 프로파일을 발급한다.

## 5. 설치 기록과 종료

`git rev-parse HEAD`, `java -version`, `docker compose version`, 실제 이미지 태그 또는 digest, Agent 버전을 검증 기록에 남긴다. [`SCENARIOS.md`](SCENARIOS.md)의 1~2개 시험을 진행한 뒤 티켓 ID와 복구 결과를 기록한다. 종료 시 각 Compose에 `stop`을 사용하고 애플리케이션 터미널을 중지한다. 로그·DB를 보존하려면 `down -v`를 사용하지 않는다.

공식 문서 [Graylog 설치](https://go2docs.graylog.org/current/downloading_and_installing_graylog.../docker_installation_os.htm), [Wazuh Docker](https://documentation.wazuh.com/current/deployment-options/docker/wazuh-container.html), [n8n Docker](https://docs.n8n.io/hosting/installation/docker/).
