#!/usr/bin/env bash
# 眼互后端部署验收脚本（WS4 / AC1-AC8 服务端可验部分）
# 用法: bash verify.sh <ECS公网IP>
# 在 ~/eye-deploy 目录下执行（与 docker-compose.yml 同级）
# 拓扑：nginx 发布 18443（WSS 终结）→ app:8080 仅本机；手机直连 wss://<公网IP>:18443/ws/eye
set -u

PUBLIC_IP="${1:?用法: bash verify.sh <ECS公网IP>}"
PASS=0
FAIL=0
ok()  { echo "✅ $1"; PASS=$((PASS+1)); }
bad() { echo "❌ $1"; FAIL=$((FAIL+1)); }
info(){ echo "ℹ️  $1"; }

echo "========== AC2 容器与端口可达 =========="
docker compose ps 2>/dev/null | grep -q "Up" && ok "compose 服务存在且已启动" || bad "compose 服务未启动(先 docker compose up -d)"

# nginx WSS 端点（手机实际入口）：405/200 即 nginx 已代理可达
CODE=$(curl -sk -o /dev/null -m 5 -w '%{http_code}' "https://${PUBLIC_IP}:18443/api/auth/login" 2>/dev/null)
if [ "$CODE" = "405" ] || [ "$CODE" = "200" ]; then
  ok "nginx WSS 端点可达(https://${PUBLIC_IP}:18443 应答 HTTP $CODE)"
else
  bad "nginx 18443 不可达(HTTP ${CODE:-无响应})，检查 nginx 容器与证书"
fi

# app 本机运维端口（仅 127.0.0.1）
CODE2=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:8080/api/auth/login" 2>/dev/null)
if [ -n "$CODE2" ] && [ "$CODE2" != "000" ]; then
  ok "app 本机 8080 有应答(HTTP $CODE2)"
else
  bad "app 127.0.0.1:8080 不可达"
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
info "AC1 公网端口扫描=0: 在非 ECS 设备上扫描 ECS 公网 IP 全端口(除 18443 外应全 closed/filtered)"
info "AC3 重启自愈: sudo reboot 后 docker compose ps 应全部自动 Up"
info "AC7 数据清理: 观察 docker logs eye-app 的『数据保留清理』日志 / 解除配对后 media 卷清空"

echo
echo "========== 结果: $PASS 通过 / $FAIL 失败 =========="
[ "$FAIL" -eq 0 ] || exit 1