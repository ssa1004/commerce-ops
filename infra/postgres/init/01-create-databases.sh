#!/bin/bash
set -e

# 서비스별 독립 DB 생성. 같은 user/password를 공유.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE orderdb;
    CREATE DATABASE paymentdb;
    CREATE DATABASE inventorydb;
EOSQL
