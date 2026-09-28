# 眼互（EyeMonitor）

> 情侣 / 好友之间的**互相守护** Demo 应用：实时看到对方打开了什么 App、在哪里，还能像 QQ 一样聊天。

两台 Android 手机通过自建的 Spring Boot 后端（局域网 WebSocket）双向互通——对方切换 App 时，聊天界面出现居中系统提示（"对方打开了微信"）；地图页实时显示双方位置；也可以随时发消息聊天。

> ⚠️ **伦理提示**：本应用属于监控类应用，**必须获得被监控方的明确同意**后方可使用。正式发布前请补充隐私声明与使用条款。

---

## 功能特性

- 💬 **QQ 风格聊天首页**：未配对 = 配对面板（输入/创建 6 位配对码）；已配对 = 聊天气泡（左白气泡 / 右珊瑚气泡）+ 居中灰色系统提示
- 📱 **App 切换监控**：`UsageStats` 轮询 + 无障碍实时监听双通道（任开其一即可），对方切换 App 即时提示
- 🗺️ **位置共享地图**：高德地图 + 大头针头像标记（自己珊瑚 / 对方蓝），顶部头像卡片点击可「去找他」
- 👥 **昵称系统**：双方昵称自动学习，聊天与地图头栏展示
- 📦 **聊天记录本地持久化**（Room），重启保留
- 🔔 前台服务 + 开机自启 + 自动重连 + 配对失效自动提示

## 技术栈

| 端 | 技术 |
|----|------|
| Android 客户端 | Java + XML（Material Components 暖色主题）、OkHttp WebSocket、高德地图/定位 SDK、Room、minSdk 24 / targetSdk 34 / Java 17 |
| 后端 | Spring Boot 3.3 + 原生 WebSocket（非 STOMP）、Jackson、Java 17、端口 8080 |

## 架构总览

```
设备 A ──UsageStats 轮询/无障碍──► 监控事件 ──► WSClient(OkHttp) ──► Spring Boot(/ws/eye)
设备 B ──► WSClient ◄── 服务器按 pairCode 转发 ◄── 设备 A 的消息
双向：chat / app_switch / location / ping / pong
```

- 配对：服务器内存维护 6 位配对码（重启失效，属设计决策）
- 协议：JSON over WebSocket，两端 `WsMessage` 字段保持一致

## 目录结构

```
eye-monitor/
├── android/                # Android 客户端（Gradle）
│   └── app/src/main/java/com/eyemonitor/
│       ├── ui/             # 聊天首页 / 地图 / 配对 / 监控页
│       ├── service/        # 前台服务、监控追踪、双通道定位
│       ├── websocket/      # OkHttp WS 客户端（自动重连+心跳）
│       ├── db/             # Room 聊天记录
│       └── config/         # SharedPreferences 封装
├── server/                 # Spring Boot 后端（Maven）
│   └── src/main/java/com/eyemonitor/
│       ├── handler/        # WebSocket 消息分发
│       └── service/        # PairService（配对码管理与路由）
├── scripts/                # 开发工具（一键授权脚本）
├── PLAN.md                 # 开发计划（需求源文档）
└── AGENTS.md               # 协作约定（改代码前先读）
```

## 快速开始

### 1. 启动后端

```bash
cd server
mvn spring-boot:run          # 监听 8080
```

验证：WebSocket 客户端连接 `ws://<局域网IP>:8080/ws/eye`，发送 `pair_request` 应返回 6 位码。

### 2. 构建 Android

```bash
cd android
./gradlew assembleDebug      # Windows: gradlew.bat
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> 服务器地址在 `PrefsManager` 中配置（默认 `ws://192.168.1.84:8080/ws/eye`，按实际局域网 IP 修改）。

### 3. 开发调试：一键授权（可选）

```bash
# 连接设备后运行，自动授予运行时权限/使用情况访问/无障碍/模拟位置
scripts/grant-dev-permissions.bat
```

> 注意：每次 `install -r` 或 force-stop 后，**无障碍开关可能被系统重置**，需重新开启。

### 4. 权限清单

| 权限 | 用途 | 获取方式 |
|------|------|---------|
| 使用情况访问权限 | App 切换监控 | 设置 → 使用情况访问（或 App 内提示跳转） |
| 无障碍服务 | App 切换实时监听（可选） | 设置 → 辅助功能 → 眼互 |
| 通知 | 前台服务 / 消息提醒 | 运行时申请（启动自动请求） |
| 位置（含后台） | 位置共享 | 运行时申请（启动自动请求） |

## 消息协议（摘要）

| type | 方向 | payload |
|------|------|---------|
| `pair_request` | 客户端→服务器 | `{ "deviceId" }` |
| `pair_confirm` | 服务器→客户端 | `{ "pairCode", "peerOnline" }` |
| `app_switch` | A→B | `{ "packageName", "appName", "action" }` |
| `location` | A→B | `{ "lat", "lng", "accuracy" }` |
| `chat` | A→B | `{ "text", "from" }` |
| `ping` / `pong` | 双向 | `{}` |

## 文档

- **PLAN.md**：开发计划与需求源文档
- **AGENTS.md**：AI 协作指南（架构、协议、构建命令、约定）——改代码前先读

## License

Demo 项目，仅供学习交流。请遵守当地法律并尊重他人隐私。
