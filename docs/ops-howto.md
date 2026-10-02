# 眼互（EyeMonitor）运维手册

> 汇总：部署/重部署、数据库操作、日常运维、安全。
> 适用：阿里云 ECS + Docker Compose 部署；访问方式 xterminal / SSH。

## 一、部署拓扑（当前）

```
手机 ── wss://<ECS公网IP>:18443/ws/eye ──► ECS nginx (18443 WSS 终结)
                                              │
                     ┌────────────────────────┘
                     ▼
                 app:8080（仅绑定 127.0.0.1，运维本机用）
                     ▼
                 mysql:3306（eye_monitor 库，仅 compose 内网，不对外）
```

- 容器：`eye-app` / `eye-mysql` / `eye-nginx`（compose 编排）
- 卷：`mysql-data`（数据库）/ `media-data`（媒体，解除配对会清空）
- 传输：nginx WSS 直连公网 18443（**无 Tailscale / 无域名**，见 ADR-6）
- ECS 安全组只放行 **22 + 18443**

## 二、服务器访问

```bash
# xterminal：连接 ECS（SFTP 文件管理器可直接拖文件）
# 或 SSH：
ssh root@<ECS公网IP>
cd ~/eye-deploy          # 部署目录（docker-compose.yml 所在）
```

## 三、部署与重部署

### 首次部署

```bash
# ECS 上：装 Docker + compose 插件；把 server/ 内容放到 ~/eye-deploy/
cd ~/eye-deploy
cp .env.example .env
# 编辑 .env，填入强随机值（openssl rand -base64 48 生成 DB_PASS/DB_ROOT_PASS/JWT_SECRET）
docker compose up -d --build
```

### 改代码后重部署（日常）

**方式 A：xterminal 手动上传（推荐给本用户）**
1. SFTP 把本地 `server/` 里改动的部分拖进 `~/eye-deploy/`：`src/`、`pom.xml`、`Dockerfile`、`docker-compose.yml`、`nginx/`（按需）
2. **三不拖**：`.env`（服务器密钥勿覆盖）/ `target/`（48M，Docker 内部自编译）/ `*.log`、`data/`
3. 终端执行：

```bash
cd ~/eye-deploy
docker compose up -d --build     # 重建+启动（Docker 内 mvn package，约 2-5 分钟）
docker compose ps                # 等 app/mysql/nginx 全部 Up
```

**方式 B：本地脚本一键**

```bash
bash scripts/deploy-server.sh <ECS公网IP>
```

（打包→scp→解包→`up -d --build`→验收；同样不覆盖 .env、不动卷）

### 只改配置 / 环境变量（代码没变）

```bash
cd ~/eye-deploy
docker compose up -d            # 不加 --build
```

### 回滚

```bash
# 本地 git log 找到旧提交 → 切回旧代码 → 按上面任一方式重传 server/
# 卷数据不受影响（mysql-data / media-data 持久化）
```

## 四、数据库操作

### 凭据位置

- **密码都在 ECS 的 `~/eye-deploy/.env`**：`DB_PASS`（应用账号 `eye`）、`DB_ROOT_PASS`（root）
- 部署时 `openssl rand -base64 48` 生成的强随机值，"忘了" = 去服务器 `cat .env`

### 进库（3306 不对外，必须进容器）

```bash
docker exec -it eye-mysql mysql -u root -p"$DB_ROOT_PASS" eye_monitor   # root
docker exec -it eye-mysql mysql -u eye -p"$DB_PASS" eye_monitor         # 应用账号
```

### 表与常用 SQL

| 表 | 内容 |
|---|---|
| `eye_users` | 用户账号 |
| `eye_pairs` | 配对关系 |
| `eye_chat_messages` | 聊天记录（kind: chat/media/system；媒体 text=fileId） |
| `eye_media_files` | 媒体文件元数据 |
| `eye_locations` | 位置历史（30 天清理） |
| `eye_sos` | SOS 求助记录 |
| `eye_fences` / `eye_folders` / `eye_remarks` / `eye_app_names` | 围栏 / 文件夹 / 备注 / App 名缓存 |

```sql
SHOW TABLES;
SELECT id, username, nickname, created_at FROM eye_users ORDER BY id;
SELECT * FROM eye_chat_messages WHERE pair_code='123456' ORDER BY ts DESC LIMIT 20;
SELECT id, file_id, file_name, mime, size FROM eye_media_files ORDER BY created_at DESC LIMIT 20;
```

### 图形工具（Navicat / DataGrip）

```bash
# ECS：转发容器 3306 → 本机 3307
docker run -d --name eye-mysql-proxy --network <compose网络名> -p 127.0.0.1:3307:3306 mysql:8.0 sleep infinity
# 本地电脑 SSH 隧道
ssh -N -L 3307:127.0.0.1:3307 root@<ECS公网IP>
# Navicat 填 localhost:3307 / eye / DB_PASS / eye_monitor
```

### 密码丢失 / .env 被覆盖（重置）

```bash
# 1. 备份卷（关键！）
docker run --rm -v <项目名>_mysql-data:/var/lib/mysql -v /root/mysql-backup:/backup alpine \
  tar czf /backup/mysql-data-$(date +%F).tgz /var/lib/mysql
# 2. 重新生成三个强随机值写进 .env
openssl rand -base64 48
# 3. 清卷重建（确认备份成功后再执行！）
docker compose down
docker volume rm <项目名>_mysql-data
docker compose up -d
```

## 五、日常运维

### 验证 / 验收

```bash
bash deploy/verify.sh <ECS公网IP>          # 或
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:18443/api/auth/login   # 405/200 = 通
docker compose ps                           # 三容器 Up
```

### 看日志

```bash
docker compose logs --tail=50 app
docker compose logs -f app                 # 实时跟
```

### 备份与恢复

- 数据库备份见上文"密码丢失"第 1 步（卷 tar 打包）
- 媒体卷同理：`docker run --rm -v <项目名>_media-data:/data/media -v /root/backup:/backup alpine tar czf /backup/media.tgz /data/media`
- 恢复：解包回同名卷后 `docker compose up -d`

### 安全注意事项

- 安全组只放行 **22 + 18443**；3306 永远不对公网
- 改/删数据前先 `SELECT` 确认；重要操作先备份
- 30 天清理策略自动清聊天/轨迹（设计如此）
- 应用账号 `eye` 只授权 eye_monitor 库，勿给 root 权限

## 六、常见问题

| 现象 | 处理 |
|---|---|
| 手机连不上 `wss://IP:18443` | `curl -sk https://127.0.0.1:18443/api/auth/login` 在 ECS 验 18443；安全组是否放行 18443；`docker compose ps` 看 nginx 是否 Up；`docker compose logs --tail=30 nginx` |
| 登录提示凭据校验失败 | `.env` 是否被 .env.example 覆盖（拖上传时误传）；`ProdCredentialValidator` 强校验，缺失/默认值拒启，看 `docker compose logs --tail=30 app` |
| 改代码后没生效 | 确认 `docker compose up -d --build`（要重建镜像）；重传时 src/ 确实覆盖了 ECS 旧文件 |
| 想连数据库没密码 | 去 ECS `cat ~/eye-deploy/.env`，见第四节 |
