"""Create fresh local-only passwords and Spring/Docker configuration."""
import hashlib
import secrets
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


def token():
    return secrets.token_hex(24)


def put(path, lines):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as file:
        file.write('\n'.join(lines) + '\n')


def main():
    targets = ('.env', 'docker/.env', 'mockbank-mysql/.env', 'mock-openbanking/.env',
               '.local/lab.env', '.local/db-users.sql')
    for path in targets:
        if (ROOT / path).exists():
            raise SystemExit(f'Existing local configuration: {path}. Use a fresh lab folder.')
    db, migrator, mock_db, mock_migrator = (token() for _ in range(4))
    graylog_password = token()
    put('.env', [
        'DB_HOST=127.0.0.1', 'DB_PORT=3306', 'DB_USERNAME=bastionpay_app',
        f'DB_PASSWORD={db}', 'DB_MIGRATION_USERNAME=bastionpay_migrator',
        f'DB_MIGRATION_PASSWORD={migrator}', f'JWT_SECRET={token()}',
        f'SECURITY_API_KEY={token()}', f'DEV_KEYSTORE_PASSWORD={token()}',
        'SERVER_ADDRESS=0.0.0.0', 'SERVER_PORT=8080', 'DEV_HTTPS=true',
        'OPENBANKING_BASE_URL=http://localhost:8090', 'OPENBANKING_CLIENT_ID=bastionpay-dev-client',
        'OPENBANKING_CLIENT_SECRET=bastionpay-dev-secret', 'OPENBANKING_ORG_CODE=M202610001',
        'OPENBANKING_PASS_PHRASE=bastionpay-dev-pass',
        'OPENBANKING_REDIRECT_URI=https://localhost:8080/openbanking/callback',
        'OPENBANKING_EXTRA_REDIRECT_URIS=', 'ADMIN_ALLOWLIST=admin@test.com',
        'LOCKOUT_ALLOWLIST=test@test.com,vip@test.com,vvip@test.com',
        'SECURITY_EVENT_LOG_FILE=logs/security-events.jsonl',
    ])
    put('docker/.env', [f'MYSQL_ROOT_PASSWORD={token()}'])
    mock_lines = [f'MOCKBANK_DB_PASSWORD={mock_db}', f'MOCKBANK_DB_MIGRATION_PASSWORD={mock_migrator}']
    put('mockbank-mysql/.env', [f'MOCKBANK_DB_ROOT_PASSWORD={token()}', *mock_lines])
    put('mock-openbanking/.env', [*mock_lines, 'SOLAPI_ENABLED=false', 'MOCKBANK_SHOW_SMS_CODE=true'])
    put('.local/lab.env', [
        f'GRAYLOG_ROOT_PASSWORD={graylog_password}',
        f'GRAYLOG_ROOT_PASSWORD_SHA2={hashlib.sha256(graylog_password.encode()).hexdigest()}',
        f'GRAYLOG_PASSWORD_SECRET={secrets.token_hex(48)}', f'N8N_ENCRYPTION_KEY={token()}',
        f'WAZUH_WEBHOOK_KEY={token()}',
    ])
    put('.local/db-users.sql', [
        f"CREATE USER IF NOT EXISTS 'bastionpay_app'@'%' IDENTIFIED BY '{db}';",
        "GRANT SELECT,INSERT,UPDATE,DELETE ON bastionpay_db.* TO 'bastionpay_app'@'%';",
        f"CREATE USER IF NOT EXISTS 'bastionpay_migrator'@'%' IDENTIFIED BY '{migrator}';",
        "GRANT ALL PRIVILEGES ON bastionpay_db.* TO 'bastionpay_migrator'@'%';",
    ])
    print('Local configuration created. Keep .env and .local private.')


if __name__ == '__main__':
    main()
