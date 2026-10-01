# 眼互 ECS 部署引导（WS4 / AC1-AC8 服务端部分）

> 前提：阿里云 ECS 已购（推荐 Ubuntu 24.04 LTS，amd64 或 arm64 均可——镜像多架构）。
> 全部命令在 ECS 的 shell 中执行（先用 `ssh ubuntu@<ECS公网IP>` 或阿里云 Workbench 登录）。

---

## ① 安全组（阿里云控制台 · 先在网页上做）

- 进入 ECS 实例 → 安全组 → 入方向规则
- **初始仅保留 22/TCP**（SSH 用；来源限制为你日常上网的 IP 或 `0.0.0.0/0` 先联通再说）
- **切勿开放 8080 / 3306** —— 这是 AC1 的核心：公网端口扫描 = 0 开放。8080 只走 tailnet
- （可选进阶）Tailscale 全部就绪后，把 22 也关掉，SSH 走 Tailscale SSH

---

## ② 系统更新 + Docker + Tailscale

```bash
sudo apt update && sudo apt -y upgrade
# Docker（官方脚本，含 compose 插件）
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker
# Tailscale
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```
- `tailscale up` 会打印登录链接，浏览器打开并登录你的 Tailscale 账号
- 记录 tailnet IP：
```bash
tailscale ip -4    # 形如 100.x.y.z ，这就是 TAILNET_IP
```

---

## ③ 上传项目（在你 Windows 本机执行）

```bash
# 进入项目 server 目录打包（排除 target/ 构建产物与 .env）
cd /e/Android/eye-monitor/server
tar czf ../server-deploy.tgz --exclude=target --exclude=.env .
# scp 到 ECS（用你的 ECS 公网 IP）
scp ../server-deploy.tgz ubuntu@<ECS公网IP>:~/
```

## ④ 解压 + 生成 .env（ECS 上执行）

```bash
mkdir -p ~/eye-deploy && cd ~/eye-deploy
tar xzf ~/server-deploy.tgz
cp .env.example .env
nano .env
```
`nano` 里填入：
- `DB_PASS` / `DB_ROOT_PASS` / `JWT_SECRET`：各跑一次 `openssl rand -base64 48` 得到强随机值
- `TAILNET_IP`：填 ② 里的 `100.x.y.z`

> `.env` 勿提交任何仓库——它本来就 gitignored。

---

## ⑤ 启动

```bash
cd ~/eye-deploy
docker compose up -d --build
docker compose ps
```
首次构建要拉 maven 镜像 + 编译，约 3-8 分钟。看到两个容器 `Up (healthy)` 即成功。

---

## ⑥ 跑验收脚本

```bash
bash verify.sh 100.x.y.z    # 换成你的 tailnet IP
```
（脚本内容见 deploy/verify.sh；手机侧 AC1/AC7 的验证步骤见下）

---

## ⑦ 手机侧（两台手机，各一次）

1. 装 **Tailscale App**（Play / F-Droid / 官网 APK），登录**同一个账号**，打开 VPN 开关，
   并：设置 → 禁用电池优化；国产 ROM 再给自启动白名单
2. 装**眼互新 APK**（含「服务器地址」入口的那个包）
3. 登录后 Profile 页 → 服务器地址 → 填 `ws://100.x.y.z:8080/ws/eye`
4. 回监控页**重新开启监控**，确认日志连上

---

## 手动验收项（脚本不覆盖）

- **AC1 公网 0 端口**：在你自己家电脑/手机上打开在线端口扫描（如 `https://portscaner.ru` 或 `nmap <ECS公网IP> -p1-65535` 限速），预期**全 closed/filtered**。8080/3306 都扫不到
- **AC7 数据清理**：跑几天后看 `docker logs eye-app` 里的 `数据保留清理完成` 日志；解除配对后 `sudo ls /var/lib/docker/volumes/*media*/_data` 应为空
- 断网/重启演习：`sudo reboot` 后 `docker compose ps` 应自动全部 `Up`（AC3）