# 眼互（EyeMonitor）数据库操作手册

适用：**云端 ECS 部署的 MySQL**（docker compose `eye-mysql` 容器）。
本地开发库同理（容器/本机 MySQL 直接连即可）。

## 一、拓扑与凭据位置

```
手机 → wss://<ECS公网IP>:18443 (nginx WSS) → app:8080 → mysql:3306 (compose 内网)
```

- MySQL 8.0 容器名 `eye-mysql`，库名 **`eye_monitor`**
- **3306 刻意不发布到公网/宿主机**（AC6），只能从容器内网访问
- 数据库账号：`eye`（应用账号，仅授权 eye_monitor 库）+ `root`（容器内维护用）
- **密码都在 ECS 的 `server/.env` 里**（部署时用 `openssl rand -base64 48` 生成的强随机值）：
  - `DB_PASS` → 应用账号 `eye` 的密码
  - `DB_ROOT_PASS` → `root` 密码

> "没有密码" = 还没去服务器读 `.env`。部署时一定落在 ECS 上了（gitignored，不进仓库）。

## 二、快速上手：SSH 进服务器读密码

```bash
ssh root@<ECS公网IP>
cd <部署目录>/server     # 通常是 ~/eye-deploy/server
cat .env                # DB_PASS / DB_ROOT_PASS 都在这里
```

## 三、进入数据库（三种姿势）

```bash
# ① root（维护/建表/授权）
docker exec -it eye-mysql mysql -u root -p"$DB_ROOT_PASS" eye_monitor

# ② 应用账号（日常查询，权限受限更安全）
docker exec -it eye-mysql mysql -u eye -p"$DB_PASS" eye_monitor

# ③ 起临时 client 容器走 compose 网络（不想动运行容器时）
docker run -it --rm --network <compose网络名> mysql:8.0 \
  mysql -h mysql -u eye -p"$DB_PASS" eye_monitor
```

> `docker network ls` 可查 compose 网络名（通常 `eye_deploy_eye` 之类，取决于目录名）。

## 四、表结构与常用查询

| 表 | 内容 | 说明 |
|---|---|---|
| `eye_users` | 用户账号 | 登录/注册；含昵称、性别、生日、bio |
| `eye_pairs` | 配对关系 | pair_code、两端 user_id、状态 |
| `eye_chat_messages` | 聊天记录 | kind: chat / media / system；text=媒体时为 fileId |
| `eye_media_files` | 媒体文件元数据 | fileId、文件名、mime、size、duration、上传者 |
| `eye_locations` | 位置历史 | 经纬度 + 时间戳（30 天清理） |
| `eye_sos` | SOS 求助记录 | 求救语、坐标、时间 |
| `eye_fences` | 电子围栏 | 围栏设置 |
| `eye_folders` | 媒体文件夹 | 相册/文件夹元数据 |
| `eye_remarks` | 备注 | 对方备注信息 |
| `eye_app_names` | App 名称缓存 | 包名→应用名映射 |

```sql
-- 看有哪些表
SHOW TABLES;

-- 用户列表
SELECT id, username, nickname, created_at FROM eye_users ORDER BY id;

-- 某配对的聊天记录（最近 20 条）
SELECT * FROM eye_chat_messages
WHERE pair_code = '123456'
ORDER BY ts DESC LIMIT 20;

-- 媒体文件清单
SELECT id, file_id, file_name, mime, size, created_at FROM eye_media_files
ORDER BY created_at DESC LIMIT 20;

-- 删除某个测试账号（慎用！先备份）
DELETE FROM eye_users WHERE username = 'test1';
```

## 五、图形工具（Navicat / DataGrip）

3306 不对外，两条路任选：

**① 临时代理容器（推荐，tailnet/安全组都不用改）**
```bash
# ECS 上执行：把 mysql:3306 转发到 ECS 本机 3307（仅 127.0.0.1）
docker run -d --name eye-mysql-proxy --network <compose网络名> \
  -p 127.0.0.1:3307:3306 mysql:8.0 sleep infinity

# 本地电脑 SSH 隧道到 ECS
ssh -N -L 3307:127.0.0.1:3307 root@<ECS公网IP>

# Navicat 填：localhost:3307，账号 eye，密码 DB_PASS，库 eye_monitor
```

**② 直接在 ECS 上用命令行**（`docker exec` 足够，见第三节）。

## 六、密码丢失 / .env 被覆盖（重置流程）

> 部署生成的密码是强随机值，无法"找回"，只能重置。

```bash
# 1. 先备份数据卷（关键！）
docker run --rm -v <项目名>_mysql-data:/var/lib/mysql \
  -v /root/mysql-backup:/backup alpine \
  tar czf /backup/mysql-data-$(date +%F).tgz /var/lib/mysql
# 2. 生成新密码写进 .env
openssl rand -base64 48   # 生成三个（DB_PASS / DB_ROOT_PASS / JWT_SECRET）
# 3. 重建（旧密码容器不认，需要清卷）
docker compose down
docker volume rm <项目名>_mysql-data   # 确认备份成功后再执行！
docker compose up -d
# 4. 恢复数据：把备份 tgz 解回新卷（或从业务侧重新同步）
```

> 清卷 = 全库数据丢失，**必须先备份**。生产上更稳的做法是 skip-grant-tables 改密，但清卷重建对 demo 部署更快、更不容易手误。

## 七、安全注意事项

- 只放行 ECS 安全组 **22 + 18443**；3306 永远不要对外开放
- 删除/修改数据前先 `SELECT` 确认范围，重要操作先备份卷
- 30 天清理策略（`DataCleanupService` 清聊天/轨迹），历史数据按设计会被自动清理
- 媒体文件在 `media-data` 卷，解除配对会清空（AC7），删库不影响已下载的媒体但服务端会丢
- 应用账号 `eye` 只该有 `eye_monitor` 库权限，勿给它赋 root 权限

## 八、本地开发库（对照）

本地跑 `mvn spring-boot:run` 时同样需要：
```bash
DB_USER=eye DB_PASS=<本地MySQL密码> JWT_SECRET=<≥32字节> mvn spring-boot:run
```
本地 MySQL 与云端 ECS 是**两个独立实例**，密码不互通；本地库直接用本机 mysql 客户端连即可。
