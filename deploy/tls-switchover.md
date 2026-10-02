# 域名 TLS 手册（twoy.online）

两条通道：**阶段① 免备案 8443 立即上线**；备案通过后走**阶段② 标准 443**。两阶段手机都**无需 Tailscale**。

---

## 阶段①（免备案，现在就能做）：WSS on 8443

> 阿里云只拦未备案域名的 **80/443**，8443 不受限；证书签发只要求域名实名（已完成），不依赖备案。

### A. 控制台两项配置
1. **安全组**：ECS 入方向增加 `TCP 8443`（仅此一条即可；80/443 等备案后再开）
2. **DNS**：`twoy.online` 加 A 记录 → `47.117.182.167`

### B. 申请证书（免费，个人可办）
1. 阿里云控制台 →「数字证书管理服务」→ 免费证书 → 创建/DNS 验证签发 `twoy.online`
2. 下载 → 选 **Nginx 格式** → 解压得到 `twoy.online.pem` / `twoy.online.key`
3. 重命名：`fullchain.pem` / `privkey.pem`
4. xterminal 上传到 ECS `~/eye-deploy/certs/`（目录不存在就新建）

### C. 上传配置（xterminal 覆盖）
本机 `server/` → ECS `~/eye-deploy/`：
- `nginx/nginx.conf`（8443 版本）
- `docker-compose.yml`（nginx 服务只发布 8443）

### D. 启动 + 验证
```bash
cd ~/eye-deploy
docker compose up -d
docker compose ps        # 应多出 eye-nginx Up
curl -s -o /dev/null -w '%{http_code}\n' https://twoy.online:8443/api/auth/login   # 405/200 即通
```

### E. 手机接入（两台，零 VPN）
1. 安装眼互新 APK（无需 Tailscale）
2. Profile → 服务器地址 → `wss://<ECS公网IP>:18443/ws/eye` → 确定 → 重启监控
3. （当前实际拓扑：公网 IP 直连 18443，未启用域名）

---

## 阶段②（备案通过后）：切标准 443

1. 安全组加开 `TCP 80` + `TCP 443`
2. `nginx/nginx.conf`：`listen 8443 ssl;` → `listen 443 ssl;`，并把 80 的 `return 301` 取消注释
3. `docker-compose.yml`：nginx ports 取消 `443:443`、`80:80` 注释，删/留 8443 均可
4. `docker compose up -d` 重建 nginx
5. 手机地址改为 `wss://twoy.online/ws/eye`（443 免端口）

## 证书续期（每年）

到期前一个月：免费证书重新签发 → 下载 Nginx 格式 → 覆盖 `~/eye-deploy/certs/` 两文件 → `docker exec eye-nginx nginx -s reload`。

## 回滚

手机地址改回 `ws://100.70.104.47:8080/ws/eye` 即回 Tailscale 通道；`docker compose stop nginx` 收起 TLS 入口。

## 安全要点
- `certs/`（私钥）已 gitignore，**勿提交**
- nginx 走容器内网 `app:8080`，后端 8080 端口绑不绑公网都无所谓
- 8443 是公网暴露口（加固模型）：依赖 TLS + JWT + 媒体归属校验 + 启动门禁，均在位