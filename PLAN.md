# 眼互（EyeMonitor）— Android 对象监控 App 开发计划

## Context

开发一款 Android 设备之间的互相监控 App（Demo 级别验证），让配对的两台手机可以实时看到对方打开了什么 App、当前位置，并在切换 App 时收到通知。技术栈统一为 Java 生态：Android 客户端用 Java + XML，后端用 Spring Boot + WebSocket，降低学习成本。

---

## 一、整体架构

```
┌───────────────────────┐         ┌───────────────────────┐
│    Android 设备 A      │         │    Android 设备 B      │
│                        │         │                        │
│  ┌─────────────────┐   │         │   ┌─────────────────┐  │
│  │ AppMonitorService │   │  JSON  │   │ AppMonitorService │  │
│  │ (UsageStats轮询)  │──┼──Web──┼──►│ (接收通知)        │  │
│  ├─────────────────┤   │ Socket │   ├─────────────────┤  │
│  │ LocationService  │   │◄──────┼───┤ LocationService  │  │
│  │ (GPS定位)        │   │       │   │ (GPS定位)        │  │
│  ├─────────────────┤   │   /ws  │   ├─────────────────┤  │
│  │ WebSocketClient  │──┼───────┼───┤ WebSocketClient  │  │
│  │ (OkHttp)         │   │       │   │ (OkHttp)         │  │
│  ├─────────────────┤   │       │   ├─────────────────┤  │
│  │ Room本地数据库    │   │       │   │ Room本地数据库    │  │
│  │ (历史记录)        │   │       │   │ (历史记录)        │  │
│  └─────────────────┘   │       │   └─────────────────┘  │
└───────────────────────┘       │       └───────────────────────┘
                                │
                    ┌───────────▼───────────┐
                    │  Spring Boot 后端       │
                    │                        │
                    │  ┌─────────────────┐   │
                    │  │ WebSocket 端点   │   │
                    │  │ (/ws/eye)       │   │
                    │  ├─────────────────┤   │
                    │  │ PairService     │   │
                    │  │ (配对码管理)     │   │
                    │  ├─────────────────┤   │
                    │  │ 消息路由转发     │   │
                    │  │ (A→B 双向)      │   │
                    │  └─────────────────┘   │
                    └────────────────────────┘
```

## 二、项目目录结构

```
eye-monitor/
├── android/                          # Android 客户端
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/eyemonitor/
│   │   │   │   ├── service/
│   │   │   │   │   ├── MonitorService.java        # 前台服务（核心）
│   │   │   │   │   ├── AppUsageTracker.java       # UsageStatsManager 封装
│   │   │   │   │   └── LocationTracker.java       # FusedLocationClient 封装
│   │   │   │   ├── websocket/
│   │   │   │   │   └── WSClient.java              # OkHttp WebSocket 客户端
│   │   │   │   ├── db/
│   │   │   │   │   ├── AppDatabase.java           # Room 数据库
│   │   │   │   │   ├── EventDao.java              # 数据访问
│   │   │   │   │   └── EventEntity.java           # 实体
│   │   │   │   ├── model/
│   │   │   │   │   ├── AppSwitchEvent.java        # App 切换事件
│   │   │   │   │   ├── LocationEvent.java         # 位置事件
│   │   │   │   │   └── WsMessage.java             # 通信协议消息
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.java          # 主界面（事件列表）
│   │   │   │   │   ├── PairActivity.java          # 配对界面
│   │   │   │   │   └── MapActivity.java           # 地图界面
│   │   │   │   ├── adapter/
│   │   │   │   │   └── EventAdapter.java          # 事件列表适配器
│   │   │   │   ├── receiver/
│   │   │   │   │   └── BootReceiver.java          # 开机自启
│   │   │   │   ├── config/
│   │   │   │   │   └── PrefsManager.java          # SharedPreferences 封装
│   │   │   │   └── EyeApp.java                    # Application 类
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   ├── activity_pair.xml
│   │   │   │   │   ├── activity_map.xml
│   │   │   │   │   └── item_event.xml
│   │   │   │   ├── drawable/
│   │   │   │   │   └── ic_notification.xml
│   │   │   │   └── values/
│   │   │   │       ├── strings.xml
│   │   │   │       └── colors.xml
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle
│   ├── build.gradle
│   └── settings.gradle
├── server/                           # Spring Boot 后端
│   ├── src/main/java/com/eyemonitor/
│   │   ├── EyeMonitorApplication.java
│   │   ├── config/
│   │   │   └── WebSocketConfig.java
│   │   ├── handler/
│   │   │   └── EyeWebSocketHandler.java
│   │   ├── model/
│   │   │   ├── WsMessage.java
│   │   │   └── PairInfo.java
│   │   └── service/
│   │       └── PairService.java
│   ├── src/main/resources/
│   │   └── application.yml
│   └── pom.xml
└── README.md
```

## 三、消息协议 (JSON over WebSocket)

### 消息格式

```json
{
  "type": "app_switch|location|pair_request|pair_confirm|ping|pong",
  "deviceId": "设备唯一标识",           // 自动生成的 UUID
  "pairCode": "6位配对码",
  "payload": { },
  "timestamp": 1234567890123
}
```

### 消息类型详解

| 类型 | 方向 | payload | 说明 |
|------|------|---------|------|
| `pair_request` | 客户端→服务器 | `{ "deviceId": "xxx" }` | 请求配对，服务器返回 6 位码 |
| `pair_confirm` | 服务器→客户端 | `{ "pairCode": "123456", "peerOnline": true/false }` | 配对成功 |
| `app_switch` | A→B | `{ "packageName": "com.xxx", "appName": "微信", "action": "OPENED" }` | App 切换事件 |
| `location` | A→B | `{ "lat": 31.23, "lng": 121.47, "accuracy": 20 }` | 位置更新 |
| `ping` | 双向 | `{}` | 心跳保活 |
| `pong` | 双向 | `{}` | 心跳响应 |

## 四、核心功能实现要点

### 4.1 App 监控（AppUsageTracker）

**核心思路：** 使用 `UsageStatsManager.queryUsageStats()` 轮询，结合 `AccessibilityService` 提高实时性。

**实现方式 1：UsageStatsManager 轮询（主要方案）**
- 每 2 秒查询一次当前前台 App
- 比较上次查询结果，发现变化时触发事件
- 需要 `android.permission.PACKAGE_USAGE_STATS`（用户手动在系统设置中开启）

```java
UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
long now = System.currentTimeMillis();
List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now);
// 按 lastTimeUsed 排序，取最新的
```

**实现方式 2：AccessibilityService（可选，提升精度）**
- 注册无障碍服务，监听 `TYPE_WINDOW_STATE_CHANGED`
- 每次切换窗口立即触发事件
- 需要用户在无障碍设置中手动开启

**两者关系：** 轮询方式用 2 秒间隔，AccessibilityService 实时触发。Demo 先用轮询就够了。

### 4.2 前台服务（MonitorService）

- 继承 `Service`，startForeground 方式运行
- 创建持久通知，显示 "眼互监控中..."
- 在 Service 中启动 App 轮询和位置上报
- 通过 `LocalBroadcastManager` 或回调将数据传给 UI

### 4.3 位置共享（LocationTracker）

- 使用 Google Play Services 的 `FusedLocationProviderClient`
- 位置更新间隔：30 秒 / 最小位移 50 米
- 需要权限：`ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`
- Demo 可以使用 `LocationManager` 作为备选（不依赖 Google Play Services）

### 4.4 WebSocket 客户端（WSClient）

- 使用 OkHttp 4.x 的 WebSocket 实现
- 连接地址：`ws://服务器IP:8080/ws/eye`
- 自动重连（指数退避：1s, 2s, 4s, 8s... 最大 30s）
- 心跳：每 30 秒发送 ping

```java
OkHttpClient client = new OkHttpClient.Builder()
    .pingInterval(30, TimeUnit.SECONDS)
    .build();
Request request = new Request.Builder().url("ws://...").build();
WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
    @Override public void onOpen(...) { /* 配对请求 */ }
    @Override public void onMessage(...) { /* 解析JSON + 本地通知 */ }
    @Override public void onFailure(...) { /* 重连 */ }
});
```

### 4.5 本地通知（接收方）

- 收到 `app_switch` 消息时，发出系统通知
- 通知内容：`"对方打开了 [App名称]"` + 时间
- 点击通知跳转到主界面

### 4.6 本地历史记录（Room）

- `EventEntity`: id, deviceId, type, appName, lat, lng, timestamp
- 使用 Room 持久化存储
- 主界面用 RecyclerView 展示历史

### 4.7 配对流程

```
设备 A ───pair_request───► 服务器 ──pair_response──► 设备 A (收到 6 位码 "123456")
设备 B ───pair_request───► 服务器 ──pair_response──► 设备 B (收到 6 位码 "123456")
                                            │
                             同一配对码 = 配对成功
                                            │
设备 A ◄──────app_switch/location─────────── 设备 B
设备 B ◄──────app_switch/location─────────── 设备 A
```

配对码手动输入方式：
1. 设备 A 点击"创建配对" → 显示 6 位码
2. 设备 B 输入 6 位码 → 点击"加入配对"
3. 服务器验证配对码，建立配对关系

### 4.8 Spring Boot 后端

- 使用 `spring-boot-starter-websocket`
- 原生 WebSocket（非 STOMP，更轻量）
- 配对管理：`ConcurrentHashMap<String, Set<WebSocketSession>>`
- 消息路由：收到消息后，根据 `pairCode` 找到对方 session，转发

```java
@Configuration
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eyeHandler(), "/ws/eye").setAllowedOrigins("*");
    }
}
```

## 五、权限清单

### Android 端

| 权限 | 用途 | 获取方式 |
|------|------|---------|
| `PACKAGE_USAGE_STATS` | 监控 App 使用 | 跳转系统设置页手动开启 |
| `FOREGROUND_SERVICE` | 前台服务 | AndroidManifest 声明 |
| `POST_NOTIFICATIONS` (Android 13+) | 发送通知 | 运行时请求 |
| `ACCESS_FINE_LOCATION` | GPS 定位 | 运行时请求 |
| `ACCESS_BACKGROUND_LOCATION` | 后台定位 | 运行时请求 |
| `INTERNET` | WebSocket 连接 | AndroidManifest 声明 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 | AndroidManifest 声明 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | AndroidManifest 声明 |

## 六、开发路线图（分 4 步）

### Step 1: 后端 + 通信验证（1 天）
- 搭建 Spring Boot 项目，配置 WebSocket
- 实现配对管理和消息转发
- 用 WebSocket 客户端测试工具验证收发

**涉及文件：**
- `server/pom.xml` — 依赖配置
- `server/.../WebSocketConfig.java` — WebSocket 端点注册
- `server/.../EyeWebSocketHandler.java` — 消息处理
- `server/.../PairService.java` — 配对码管理
- `server/.../WsMessage.java` — 消息模型
- `server/.../application.yml` — 端口配置

### Step 2: Android 监控服务（1-2 天）
- 创建 Android 项目，配置 Gradle 依赖
- 实现 `MonitorService` 前台服务
- 实现 `AppUsageTracker` 轮询 App 切换
- 实现 `WSClient` WebSocket 连接
- 实现配对和通知
- 在后台跑通：App 切换 → WebSocket → 服务器 → 通知到另一台手机

**涉及文件：**
- `android/app/build.gradle` — 依赖（OkHttp, Room, Gson 等）
- `android/.../AndroidManifest.xml` — 权限和服务声明
- `android/.../service/MonitorService.java` — 前台服务
- `android/.../service/AppUsageTracker.java` — App 监控轮询
- `android/.../websocket/WSClient.java` — WebSocket 客户端
- `android/.../model/WsMessage.java` — 消息模型
- `android/.../config/PrefsManager.java` — 配对信息存储

### Step 3: 位置共享 + 地图（1 天）
- 实现 `LocationTracker` 定位
- 在 MonitorService 中周期上报位置
- 实现 `MapActivity` 使用百度地图/高德地图 SDK 显示对方位置
- 收到位置更新时显示在地图上

**涉及文件：**
- `android/.../service/LocationTracker.java` — 定位封装
- `android/.../ui/MapActivity.java` — 地图界面
- `android/.../layout/activity_map.xml` — 地图布局

### Step 4: UI 界面完善 + 历史记录（1 天）
- 实现 `MainActivity` 事件列表（RecyclerView）
- 实现 `PairActivity` 配对界面
- 实现 Room 数据库存历史记录
- 三个界面的菜单导航

**涉及文件：**
- `android/.../ui/MainActivity.java` — 主界面
- `android/.../ui/PairActivity.java` — 配对界面
- `android/.../layout/activity_main.xml` — 主布局
- `android/.../layout/activity_pair.xml` — 配对布局
- `android/.../layout/item_event.xml` — 列表项布局
- `android/.../adapter/EventAdapter.java` — 列表适配器
- `android/.../db/*` — Room 数据库

## 七、关键技术决策

### 7.1 为什么不用 AccessibilityService 做主要方案？
- 轮询已经能满足 Demo 需求
- AccessibilityService 需要用户额外开启无障碍，多一步操作
- 但 AccessibilityService 在轮询方案基础上可以叠加，作为后期优化

### 7.2 地图 SDK 选择
- **推荐高德地图**：国内定位更准，免费额度充足
- 备选：百度地图
- Demo 也可以只在 WebView 中显示一个静态地图链接，不依赖 SDK

### 7.3 服务器部署
- Demo 阶段：本地局域网运行
- 配对码：6 位数字，内存中维护，重启后失效
- 不需要持久化存储

## 八、Demo 验证清单

- [ ] 两台手机连接到同一服务器
- [ ] 输入配对码，成功配对
- [ ] 手机 A 打开微信，手机 B 收到通知："对方打开了 [微信]"
- [ ] 手机 A 切换到抖音，手机 B 收到通知："对方切换到 [抖音]"
- [ ] 手机 B 地图上看到手机 A 的位置
- [ ] 手机 A 地图上看到手机 B 的位置
- [ ] 查看历史记录列表
- [ ] 关闭 App 后重启，服务自动恢复

## 九、风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| Android 系统杀后台服务 | 监控中断 | 前台服务 + 通知，降低被杀概率 |
| 用户关闭「使用情况访问权限」 | App 监控失效 | 引导页面提示开启 |
| 后台定位耗电 | 电池消耗快 | Demo 阶段 30 秒间隔可接受 |
| 局域网 IP 变化 | 连接断开 | 自动重连机制处理 |
| Android 10+ 后台定位限制 | 定位不准 | 申请后台定位权限，引导用户 |