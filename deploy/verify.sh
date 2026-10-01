#!/usr/bin/env bash
# 眼互后端部署验收脚本（WS4 / AC1-AC8 服务端可验部分）
# 用法: bash verify.sh <ECS-tailnet-IP>
# 在 ~/eye-deploy 目录下执行（与 docker-compose.yml 同级）
set -u

TAILNET_IP="${1:?用法: bash verify.sh <ECS-tailnet-IP>}"
PASS=0
FAIL=0
ok()  { echo "✅ $1"; PASS=$((PASS+1)); }
bad() { echo "❌ $1"; FAIL=$((FAIL+1)); }
info(){ echo "ℹ️  $1"; }

echo "========== AC2 容器与端口可达 =========="
docker compose ps 2>/dev/null | grep -q "Up" && ok "compose 服务存在且已启动" || bad "compose 服务未启动(先 docker compose up -d)"
CODE=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://${TAILNET_IP}:8080/api/auth/login" 2>/dev/null)
if [ "$CODE" != "000" ] && [ -n "$CODE" ]; then
  ok "tailnet IP:8080 有应答(HTTP $CODE)"
else
  bad "tailnet IP:8080 不可达"
fi

echo "========== AC6 MySQL 不发布宿主端口 =========="
if docker compose port mysql 2>/dev/null | grep -q .; then
  bad "mysql 存在宿主端口映射(应仅 compose 内网可达)"
else
  ok "mysql 未发布端口(公网/宿主均扫不到 3306)"
fi

echo "========== AC8 容器非 root =========="
UID_OUT=$(docker exec eye-app id -u 2>/dev/null)
if [ "$UID_OUT" = "10001" ]; then
  ok "app 容器 uid=10001(非 root)"
else
  bad "app 容器 uid=${UID_OUT:-获取失败}"
fi

echo "========== AC5 .env 无占位/默认凭据 =========="
if grep -qE "REPLACE_WITH|EyeDev2025_local|eye-monitor-2025-local-dev" .env 2>/dev/null; then
  bad ".env 仍含占位符或历史默认凭据!"
else
  ok ".env 无占位符/默认凭据"
fi

echo "========== WS1 凭据门禁（当前容器启动状态即证明） =========="
if docker exec eye-app sh -c 'ls /app/app.jar' >/dev/null 2>&1; then
  ok "app 容器运行中(若含默认凭据 ProdCredentialValidator 会拒停，既然在跑说明凭据已过校验)"
else
  info "app 容器未运行，检查 docker logs eye-app"
fi

echo "========== 未覆盖项(手动) =========="
info "AC1 公网端口扫描=0: 在非 ECS 设备上扫描 ECS 公网 IP 全端口(应全 closed/filtered)"
info "AC3 重启自愈: sudo reboot 后 docker compose ps 应全部自动 Up"
info "AC7 数据清理: 观察 docker logs eye-app 的『数据保留清理』日志 / 解除配对后 media 卷清空"

echo
echo "========== 结果: $PASS 通过 / $FAIL 失败 =========="
[ "$FAIL" -eq 0 ] || exit 1