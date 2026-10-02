#!/usr/bin/env bash
# 本地后端启动（读 server/.env 凭据；.env gitignored，包含稳定的 JWT_SECRET，
# 避免每次重启换 secret 导致手机端 token 失效重登）
set -euo pipefail
cd "$(dirname "$0")/../server"

if [ ! -f .env ]; then
  echo "缺少 server/.env（先执行: openssl rand -base64 48 生成 JWT_SECRET 写入）" >&2
  exit 1
fi

set -a; source .env; set +a
exec mvn -q spring-boot:run
