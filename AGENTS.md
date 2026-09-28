# AGENTS.md — 眼互（EyeMonitor）项目协作指南

本文档面向 AI 编码代理与人类开发者，说明本项目（基于 `PLAN.md` 的恋爱监视 App）的结构、构建方式、协议与开发约定。改代码前先读本文档与 `PLAN.md`。

---

## 一、项目概述

**眼互（EyeMonitor）**：Android 设备之间的互相监控 App（Demo 级验证）。配对的两台手机通过 Spring Boot 后端实时互看对方打开了什么 App、当前位置，并在对方切换 App 时收到系统通知。

- 技术栈：**全 Java 生态**
  - Android 客户端：Java + XML 布局（不用 Kotlin/Compose）
  - 后端：Spring Boot 3.3 + 原生 WebSocket（非 STOMP）
- 目标平台：Android 7.0+（minSdk 24），targetSdk 34，Java 17
- 服务器：本地局域网运行，端口 **8080**

## 二、架构总览

```
设备 A ──UsageStats 轮询──► 监控事件 ──► WSClient(OkHttp) ──► Spring Boot 后端(/ws/eye)
设备 B ──► WSClient ◄── 服务器按 pairCode 转发 ◄── 设备 A 的消息
双向：app_switch / location / ping / pong
```

- **数据流**：`AppUsageTracker`（轮询前台 App）→ `MonitorService`（前台服务）→ `WSClient`（OkHttp WebSocket）→ 服务器路由 → 对方手机 → 系统通知 + Room 历史（可选）
- **配对**：服务器内存中维护 6 位配对码（`PairService`，`ConcurrentHashMap<String, Set<WebSocketSession>>`），重启失效，无需持久化
- **通信协议**：JSON over WebSocket，见下文第四节

## 三、目录结构（当前实际状态）

```
eye-monitor/
├── AGENTS.md                     # 本文档
├── PLAN.md                       # 开发计划（权威需求文档，改功能前先读）
├── android/                      # Android 客户端（Gradle 项目，rootProject: EyeMonitor）
│   ├── build.gradle
│   ├── settings.gradle
│   └── app/
│       ├── build.gradle          # 依赖：OkHttp 4.12, Gson 2.10, AndroidX, 高德 3D SDK
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/eyemonitor/
│           │   ├── EyeApp.java              # Application（全局单例/初始化）
│           │   ├── config/PrefsManager.java # SharedPreferences 封装（配对信息、设备ID）
│           │   ├── model/WsMessage.java     # 通信协议消息模型
│           │   ├── service/
│           │   │   ├── MonitorService.java  # 前台服务（核心，约 513 行）
│           │   │   ├── AppUsageTracker.java # UsageStatsManager 轮询封装
│           │   │   ├── AppAccessibilityService.java # 无障碍实时监听（增强方案）
│           │   │   └── LocationTracker.java # 定位封装（FusedLocation / LocationManager 备选）
│           │   ├── websocket/WSClient.java  # OkHttp WebSocket 客户端（自动重连+心跳）
│           │   ├── receiver/BootReceiver.java # 开机自启
│           │   ├── ui/
│           │   │   ├── MainActivity.java    # 主界面（事件列表）
│           │   │   ├── MonitorActivity.java # 监控服务开关界面
│           │   │   ├── PairActivity.java    # 配对界面
│           │   │   └── MapActivity.java     # 高德地图界面
│           │   └── util/AccessibilityDiagnostic.java # 无障碍诊断工具
│           └── res/
│               ├── layout/       # activity_main/monitor/pair/map + item_app_switch.xml
│               ├── values/       # strings.xml / colors.xml / themes.xml
│               ├── xml/accessibility_service_config.xml
│               └── mipmap*/drawable*/
└── server/                       # Spring Boot 后端（Maven）
    ├── pom.xml                   # spring-boot-starter-websocket, Jackson, Java 17
    └── src/main/
        ├── java/com/eyemonitor/
        │   ├── EyeMonitorApplication.java
        │   ├── config/WebSocketConfig.java     # 注册 /ws/eye
        │   ├── handler/EyeWebSocketHandler.java # 消息分发入口
        │   ├── model/WsMessage.java            # 消息模型（与客户端对齐）
        │   └── service/PairService.java        # 配对码管理与消息路由（约 527 行）
        └── resources/application.yml           # 端口 8080
```

> 注意：`PLAN.md` 中的 `db/`（Room）、`adapter/`、`EventDao` 等目录当前**尚未实现**——按路线图属于 Step 4。若任务涉及历史记录功能，需从零创建。
>
> **2025-08 更新：** 首页已改为双视图（未配对=配对面板：输入码+确认+创建码；已配对=QQ 风格聊天首页，见下）。Room 数据库层已实现（`db/ChatEntity` + `ChatDao` + `AppDatabase`，单例 + `dbExecutor` 线程池，**禁止主线程操作 Room**）。`app_switch` 在聊天首页以居中灰色系统提示展示（“对方打开了 X”），`MonitorActivity` 仍保留原列表视图。
>
> **聊天首页要点：** 发送聊天走 `MonitorService.sendChat(context, text, from)` 静态方法（Intent action `SEND_CHAT`，服务内发 WS）；收到 `chat` 消息由 `MonitorService.handleMessage` 广播 `ACTION_EVENT`，`MainActivity` 渲染三态 item（`item_chat_self` 右侧珊瑚气泡 / `item_chat_peer` 左侧白气泡+昵称 / `item_chat_system` 居中灰字）；昵称存 `PrefsManager`（`getNickname/setNickname` 我的昵称，`getPeerNickname/setPeerNickname` 对方昵称——从收到的 `chat.from` 学习）。解除配对走头栏更多菜单，会清空本地聊天记录。后续 MySQL 持久化属于后端阶段，当前聊天记录仅本地 Room。

## 四、消息协议（两端必须保持一致）

### 消息格式

```json
{
  "type": "app_switch | location | pair_request | pair_confirm | ping | pong",
  "deviceId": "设备唯一标识(UUID)",
  "pairCode": "6位配对码",
  "payload": { },
  "timestamp": 1234567890123
}
```

### 类型明细

| type | 方向 | payload | 说明 |
|------|------|---------|------|
| `pair_request` | 客户端→服务器 | `{ "deviceId" }` | 创建配对，服务器返回 6 位码 |
| `pair_confirm` | 服务器→客户端 | `{ "pairCode", "peerOnline" }` | 配对成功通知 |
| `app_switch` | A→B | `{ "packageName", "appName", "action": "OPENED" }` | App 切换事件 |
| `location` | A→B | `{ "lat", "lng", "accuracy" }` | 位置更新 |
| `chat` | A→B | `{ "text", "from" }` | 聊天消息（QQ 风格首页），from 为发送者昵称 |
| `ping` / `pong` | 双向 | `{}` | 心跳保活 |

**修改协议时的硬性要求：** 必须同步修改 `android/.../model/WsMessage.java` 与 `server/.../model/WsMessage.java` 两处，并检查两端字段名/类型一致。字段名用小驼峰。

## 五、构建与运行命令

### 后端（server/）

```bash
cd server
mvn spring-boot:run              # 开发运行，监听 8080
# 或打包后运行
mvn package && java -jar target/eye-monitor-server-0.0.1-SNAPSHOT.jar
```

验证：WebSocket 客户端工具连 `ws://<局域网IP>:8080/ws/eye`，发 `pair_request` 应返回 6 位码。

### Android（android/）

```bash
cd android
./gradlew assembleDebug          # 构建 debug APK（Windows: gradlew.bat）
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

- 手机连接调试：Android Studio 直接 Run，或用 `adb install`
- 服务器地址在客户端代码/`PrefsManager` 中配置（局域网 IP + 端口 8080）
- **改 Java/XML 后必须重新构建验证，不要假设编译通过**

## 六、Android 端关键实现约定

1. **监控主方案**：`AppUsageTracker` 用 `UsageStatsManager.queryUsageStats()` 每 2 秒轮询，比较前台 App 变化后触发事件。需要用户在系统「使用情况访问」设置中手动授权 `PACKAGE_USAGE_STATS`（代码无法运行时申请，只能跳设置页）。
2. **无障碍增强**：`AppAccessibilityService` 监听 `TYPE_WINDOW_STATE_CHANGED` 实时触发，可选叠加。改它时注意 `accessibility_service_config.xml` 同步。
3. **前台服务**：`MonitorService` 必须 `startForeground()` 带持续通知（"眼互监控中..."），Android 8.0+ 必须指定通知渠道（NotificationChannel），Android 13+ 需运行时请求 `POST_NOTIFICATIONS`。
4. **定位**：30 秒 / 最小位移 50 米。需 `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` 运行时申请。**`LocationTracker` 已改双通道**：高德 SDK 为主（调试签名下可能 auth fail，需配置 API Key 签名）+ 系统 `LocationManager`（GPS/NETWORK provider）兜底——模拟器 `adb emu geo fix <lng> <lat>` 注入位置后系统通道可直接工作，不依赖高德 Key。
4.1 **开发权限（2025-08）**：开发调试免手动授权跑 `scripts/grant-dev-permissions.bat`（pm grant 通知/位置 + appops usage stats + settings 无障碍 + 模拟位置注入）；App 启动时自动请求缺失运行时权限；注意**每次 `install -r` 或 force-stop 会重置无障碍启用状态**，需重新开启。
5. **WSClient**：OkHttp 4.x WebSocket；指数退避重连（1s→30s 上限）；`pingInterval(30, SECONDS)` 心跳。
6. **UI 一律 Java + XML**，不用 Kotlin/Compose；字符串放 `strings.xml`，颜色放 `colors.xml`，禁止硬编码中文字符串在 Java 里（除非是临时 log）。
6.1 **视觉体系（2025-08 已落地）**：主题 = `Theme.MaterialComponents.Light.NoActionBar`（依赖 `material:1.11.0` 已就位），恋爱暖色盘（primary `#FF6B6B` / accent `#FFA26B` / 背景 `#FFF9F7`）；组件样式集中在 `res/values/styles.xml`（`Widget.EyeMonitor.Button.Primary/Outlined/Header`、`EditText`）；通用 drawable 在 `res/drawable/`（渐变头栏 `bg_header_gradient`、圆角卡片 `bg_card*`、地图 marker `marker_pin`、气泡 `bg_info_window`）；地图 marker/气泡自定义在 `MapActivity.java` 的 `setInfoWindowAdapter` + `info_window.xml`。改 UI 时优先复用这些设计令牌，勿直接堆叠新色值。
6.2 **地图行为约定（2025-08）**：自己的位置只用 `selfMarker`（HUE_ROSE 粉标）渲染——高德定位蓝点图标已设透明避免双图标；对方用 `marker_pin` 珊瑚 pin（peerMarkers 按 deviceId 去重）；用户滑动地图后 **8 秒内不自动聚焦**（`setOnMapTouchListener` + `MAP_IDLE_FOCUS_MS`，`adjustCameraOnce` 前置检查 `userDraggingMap`）。
7. **通知点击跳转**：`MainActivity`，注意 `FLAG_IMMUTABLE`（API 31+ PendingIntent 必填）。
8. 开机自启：`BootReceiver` 监听 `RECEIVE_BOOT_COMPLETED`。

## 七、后端关键实现约定

1. **路由逻辑在 `PairService`**：收到消息按 `pairCode` 找对方 session 转发；同一 pairCode 下多个 session 都要覆盖。
2. 配对码 6 位数字，`SecureRandom` 生成，内存态（`ConcurrentHashMap`），重启失效——这是设计决策，不是缺陷。
3. 原生 WebSocket 端点 `/ws/eye`，`setAllowedOrigins("*")`；JSON 解析用 Jackson `ObjectMapper`。
4. 消息处理注意线程安全：session 集合操作加锁或用 `ConcurrentHashMap.newKeySet()`；发送走 `session.sendMessage`，注意 `TextMessage` UTF-8 编码（中文 appName）。
5. `application.yml` 已有 `-Dfile.encoding=UTF-8` jvmArguments，Windows 下中文不乱码依赖它，别去掉。

## 八、开发路线（PLAN.md 六节，共 4 步）

| Step | 内容 | 状态 |
|------|------|------|
| 1 | 后端 + WebSocket 通信验证 | ✅ 已实现 |
| 2 | Android 监控服务 + 配对 + 通知 | ✅ 已实现 |
| 3 | 位置共享 + 高德地图 | ✅ 已实现 |
| 4 | UI 完善 + Room 历史记录 | ⚠️ 部分（db/、adapter/ 未实现） |

当前主线工作通常是：**补全 Step 4 的 Room 历史记录（EventEntity/EventDao/AppDatabase/EventAdapter）**，或按需修复调试监控链路。

## 九、验证清单（改动后自检）

- [ ] 两端 `WsMessage.java` 字段一致
- [ ] Android 编译通过：`./gradlew assembleDebug`（Windows 用 `gradlew.bat`）
- [ ] 服务端编译通过：`mvn package`（或至少 `mvn compile`）
- [ ] 新增权限 → 已在 `AndroidManifest.xml` 声明 + 运行时请求逻辑
- [ ] 新增字符串/颜色 → 已放入 `res/values/*.xml`
- [ ] 中文文本正常（UTF-8，两端配置文件均已设置）
- [ ] 后台行为符合 Android 8+/13+ 限制（通知渠道、FLAG_IMMUTABLE、后台定位）

## 十、注意事项

- **伦理/合规**：本 App 为监控类应用，需获得被监控方明确同意；若涉及正式发布，应补充隐私声明与使用条款（Demo 阶段不强制，但别在交付物里宣称"无需同意"）。
- 高德地图 SDK 需要申请 API Key 并配置在 `AndroidManifest.xml` 的 meta-data 中；`LocationTracker` 提供不依赖 GMS 的 `LocationManager` 备选。
- `PLAN.md` 是需求源文档，本文档是协作约定；两者冲突时以最新需求讨论为准，但改动需求应先在 `PLAN.md` 同步。
