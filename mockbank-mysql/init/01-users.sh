#!/bin/bash
# 데이터 볼륨을 처음 만들 때 한 번만 실행된다. 앱 계정(DML)과 마이그레이션 계정(DDL)을 나눠 만든다.
set -euo pipefail

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER 'mockbank_app'@'%' IDENTIFIED BY '${MOCKBANK_DB_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON mockbank_db.* TO 'mockbank_app'@'%';

CREATE USER 'mockbank_migrator'@'%' IDENTIFIED BY '${MOCKBANK_DB_MIGRATION_PASSWORD}';
GRANT ALL PRIVILEGES ON mockbank_db.* TO 'mockbank_migrator'@'%';
FLUSH PRIVILEGES;
SQL
