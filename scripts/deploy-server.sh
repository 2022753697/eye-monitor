#!/usr/bin/env bash
# 眼互后端一键重新部署（本地改完 server/ 代码后用）
# 用法: bash scripts/deploy-server.sh <ECS公网IP> [部署目录] [ssh用户]
# 流程: 本地打包 → scp → 解包到 ECS → docker compose up -d --build → 简单验收
# 安全: 不覆盖 ECS 上的 server/.env（密钥保留在服务器）；排除 target/日志/数据；volumes 不动 → 数据零丢失
set -euo pipefail

IP="${1:?用法: bash deploy-server.sh <ECS公网IP> [部署目录] [ssh用户]}"
DIR="${2:-~/eye-deploy}"
SSH_USER="${3:-root}"
PACK=/tmp/eye-server-deploy.tgz

cd "$(dirname "$0")/.."

echo "== 1/4 本地打包（排除 .env / target / 日志 / data）=="
tar czf "$PACK" \
  --exclude='server/.env' \
  --exclude='server/target' \
  --exclude='server/data' \
  --exclude='server/*.log' \
  --exclude='server/console*' \
  --exclude='server/test_*.py' \
  server/
ls -lh "$PACK" | awk '{print "   包大小:", $5}'

echo "== 2/4 上传并解包到 ${SSH_USER}@${IP}:${DIR} =="
scp "$PACK" "${SSH_USER}@${IP}:/tmp/"
ssh "${SSH_USER}@${IP}" "mkdir -p ${DIR} && tar xzf /tmp/eye-server-deploy.tgz -C ${DIR} --strip-components=1 && rm /tmp/eye-server-deploy.tgz"

echo "== 3/4 重建镜像并启动（Docker 内跑 mvn package，阿里云镜像源，约 2-5 分钟）=="
ssh "${SSH_USER}@${IP}" "cd ${DIR} && docker compose up -d --build"

echo "== 4/4 验收 =="
sleep 8
ssh "${SSH_USER}@${IP}" "cd ${DIR} && docker compose ps"
CODE=$(ssh "${SSH_USER}@${IP}" "curl -sk -o /dev/null -m 5 -w '%{http_code}' https://127.0.0.1:18443/api/auth/login 2>/dev/null" || true)
if [ "$CODE" = "405" ] || [ "$CODE" = "200" ]; then
  echo "✅ WSS 端点 18443 应答 HTTP $CODE —— 部署成功"
else
  echo "⚠️  18443 无应答(HTTP ${CODE:-无})，看日志: ssh ${SSH_USER}@${IP} 'cd ${DIR} && docker compose logs --tail=50 app'"
fi

echo "== 完成。手机端无需改服务器地址（wss://${IP}:18443/ws/eye）=="