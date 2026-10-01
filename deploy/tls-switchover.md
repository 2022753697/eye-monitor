# 域名 TLS 切换手册（twoy.online · ICP 备案通过后执行）

> 前置：ICP 备案已通过（拿到备案号）。等待期内手机继续用 Tailscale + `ws://100.70.104.47:8080`，本文全部步骤**备案号下来后**才做。

---

## ① 安全组（阿里云控制台）

ECS 安全组入方向**增加两条**：
- `TCP 80`（HTTP 跳转/挑战）
- `TCP 443`（WSS/REST）

> ⚠️ 备案通过前**不要**开这两条——未备案域名 80/443 会被阿里云拦截，开了也没用。

## ② 申请证书（阿里云免费证书，DV 单域名，一年有效期）

1. 控制台 → 搜索「**数字证书管理服务**」→ 免费证书 → 创建证书
2. 域名填 `twoy.online`（个人可申请，自动签发）
3. 签发后 → 下载 → **选「Nginx」格式** → 解压得到两个文件：
   - `twoy.online.pem`（证书链）→ **重命名 `fullchain.pem`**
   - `twoy.online.key`（私钥）→ **重命名 `privkey.pem`**
4. xterminal 上传到 ECS 的 `~/eye-deploy/certs/`（目录不存在就新建）

## ③ 上传切换文件（xterminal 覆盖）

从本机仓库 `server/` 拖到 `~/eye-deploy/`：
- `nginx/nginx.conf`（新目录）
- `docker-compose.yml`（新增了 nginx 服务）

## ④ 启动 nginx（证书就位后）

```bash
cd ~/eye-deploy
docker compose up -d
docker compose ps
```
预期：新增 `eye-nginx Up`；`eye-app` / `eye-mysql` 保持不变。

## ⑤ 验证

```bash
# REST 可达（405 也是可达：/api/auth/login 只接受 POST）
curl -s -o /dev/null -w '%{http_code}\n' https://twoy.online/api/auth/login
# 证书链
curl -sI https://twoy.online | grep -i "HTTP\|subject"
```

## ⑥ 手机切换（两台）

1. 眼互 Profile → 服务器地址 → 改 `wss://twoy.online/ws/eye` → 确定 → 重启监控
2. 确认连上后，**卸载 Tailscale**（两台）——正式切换完成
3. ECS 的 tailscale 可保留（远程 SSH 运维备用）或 `sudo tailscale down`

## ⑦ 收紧（可选但推荐）

把 `docker-compose.yml` 中 app 的端口绑定从 `TAILNET_IP:8080` 改为 `127.0.0.1:8080`（nginx 走容器内网 `app:8080`，不受影响；公网/tailnet 都不再暴露 8080），然后 `docker compose up -d` 重建 app。

## 证书到期续期（每年）

阿里云免费证书到期前一个月：控制台重新创建 → 签发 → 下载 Nginx 格式 → xterminal 覆盖 `~/eye-deploy/certs/` 两个文件 → `docker exec eye-nginx nginx -s reload`。

## 回滚

任何一步出问题：手机地址改回 `ws://100.70.104.47:8080/ws/eye`，重启监控（Tailscale 若已卸则重装或改用 ⑦ 之前的状态）。nginx 容器 `docker compose stop nginx` 即可关闭 443 入口。

---

### 已知要点
- 证书文件 `certs/` 已在 `.gitignore`，**勿提交仓库**
- nginx 反代是容器内网 `app:8080`，与 app 端口绑定无关（④ 不动 app 也能跑）
- WSS 走 443 标准端口，手机浏览器可直接打开 `https://twoy.online` 验证证书有效
