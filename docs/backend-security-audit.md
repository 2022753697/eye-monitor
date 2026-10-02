# 眼互 EyeMonitor 后端安全审计与加固计划

> 审计对象：`server/`（Spring Boot 3.3.2 + 原生 WebSocket + Jackson + JPA/MySQL，Java 17）
> 审计方式：**静态代码审查（只读）**，未运行任何攻击、未修改任何代码文件。
> 审计日期：2026-10-02（基于当前工作区代码快照）
> 结论概览：整体安全基线良好（启动凭据强校验、非 root 容器、媒体归属校验、ver 吊销、无 SQL 拼接注入面），但存在 **1 个 Critical、3 个 High** 级问题，核心集中在「配对码暴力枚举 + WebSocket 路由身份信任客户端」两条链路上，详见下文。

---

## 1. 排查方法说明（读了哪些文件）

### 1.1 逐文件审读（全部 `server/src/main/java`，共 51 个 Java 文件）

| 包 | 文件 |
|---|---|
| security | `JwtUtil.java`、`AuthInterceptor.java`、`AuthUtil.java`、`WsAuthInterceptor.java`、`WsSessionManager.java` |
| handler | `EyeWebSocketHandler.java`（WS 消息全部分发逻辑） |
| service | `AuthService.java`、`PairService.java`（配对+路由核心）、`TaskService.java`（状态机）、`MessageStore.java`、`MediaService.java`、`LocationThrottle.java`、`DataCleanupService.java` |
| controller | `AuthController.java`、`MediaController.java`、`TaskController.java`、`ChatController.java`、`TrackController.java`、`RemarkController.java`、`PairController.java`、`UserController.java`、`FenceController.java`、`FolderController.java`、`AnniversaryController.java`、`AppNameController.java` |
| config | `WebMvcConfig.java`、`WebSocketConfig.java`、`ProdCredentialValidator.java`、`AppNameSeeder.java` |
| entity/repository | 13 个实体 + 13 个 Repository（重点核对有无 `@Query` 拼接 SQL） |
| web/model/util | `GlobalExceptionHandler.java`、`ApiResponse.java`、`BizException.java`、`WsMessage.java`、`ProfileView.java`、`EyeMonitorApplication.java` |

### 1.2 配置与部署文件

- `pom.xml`（依赖版本基线）、`src/main/resources/application.yml`、`.env.example`、`.env`（仅核对键名与权限，未外泄值）
- `docker-compose.yml`、`Dockerfile`、`nginx/nginx.conf`
- `docs/ops-howto.md`、`deploy/tls-switchover.md`、`deploy/verify.sh`
- 测试：`ProdCredentialValidatorTest`、`WsMessageTest`、`LocationThrottleTest`
- 客户端参照：`android/.../WSClient.java`、`PrefsManager.java`（确认 WS 握手 token 传递方式与 token 存储）

### 1.3 用到的核验手段

- `grep` 检索 `@Query / nativeQuery / StringBuilder / concat` 确认无 SQL 拼接注入面
- `git ls-files / check-ignore` 确认 `.env` 未被纳入版本库（`.gitignore:49` `server/.env`，仅 `.env.example` 入库）
- 关键 CVE 版本事实经 web 检索核对（Spring Framework CVE-2024-38816/38819/38820、Tomcat CVE-2024-50379/52316/56337 的修复版本）

---

## 2. 发现清单

严重级别：Critical / High / Medium / Low / Info。每条含攻击路径（可复现思路）、位置、修复建议（含示例代码）。

### F-01 【Critical】6 位配对码暴力枚举可劫持未完成配对 → 情侣隐私数据全量泄露

- **位置**：`service/PairService.java:60-75`（`handlePairRequest`）、`:96-132`（`createPair`/`joinOrRecover`）、`entity/PairEntity.java`（STATUS_PENDING 无过期）；`handler/EyeWebSocketHandler.java:73-75`
- **攻击路径**：
  1. 配对码空间仅 `10^6`（`String.format("%06d", random.nextInt(1_000_000))`），任何登录用户可通过 `pair_request` 提交任意 6 位码；
  2. `joinOrRecover` 对「非成员」且「userB 空位」的配对直接写入 `userB=攻击者`，**无尝试次数限制、无验证码、无码过期机制**（PENDING 状态永久有效，仅服务器重启后靠 DB 恢复，`loadFromDb` 也不清理过期）；
  3. 加入成功后 `belongsToPair(攻击者, 该码)=true`，攻击者即可全量读取该对情侣的 `/api/chats/{code}`、`/api/tasks/{code}`、`/api/tracks/{code}`、媒体列表与下载（各 Controller 的 `belongsToPair` 校验全部放行）；
  4. 在线枚举约 1e6 次请求，配合 F-05 注册无限制可多账号并发，数小时~数天量级可扫中一个活跃 PENDING 码。
- **可复现思路**：注册两个账号 A/B；A 建配对（不邀请）；攻击者账号 C 连 WS 后循环发 `pair_request` 枚举 6 位码；命中后 C 成为 userB，`GET /api/chats/{code}` 返回 A 的聊天记录。
- **修复建议（组合拳，P0）**：
  1. 加入尝试限流：按用户/IP 对 `pair_request` 失败尝试滑动窗口计数（如 10 次/小时，超出锁码 30 分钟）；
  2. 配对码生命周期：创建后 30 分钟内未 COMPLETE 即作废（DB 定时 + 内存 lazy check）；
  3. 可选增强：第二成员加入时向 userA 推送待确认事件，A 确认后绑定才生效（改协议，放 P1/P2）；
  4. 日志不再输出完整配对码（见 F-13）。

  ```java
  // 示例：尝试限流（内存滑动窗口，多实例时换 Redis）
  public class PairAttemptLimiter {
      private final ConcurrentHashMap<String, Deque<Long>> fails = new ConcurrentHashMap<>();
      private static final int MAX_FAIL = 10;            // 10 次
      private static final long WINDOW_MS = 3_600_000L;  // 每小时

      public boolean allowed(String key) {
          long now = System.currentTimeMillis();
          Deque<Long> q = fails.computeIfAbsent(key, k -> new ArrayDeque<>());
          synchronized (q) {
              while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
              return q.size() < MAX_FAIL;
          }
      }
      public void recordFail(String key) { /* 入队 now */ }
      public void recordSuccess(String key) { fails.remove(key); }
  }
  // 在 PairService.handlePairRequest 的无效码分支调用：allowed("pair:"+userId) 与 allowed("pair:ip:"+ip)
  ```

### F-02 【High】WebSocket 路由信任客户端 deviceId/pairCode，发送者身份与配对绑定未校验

- **位置**：`handler/EyeWebSocketHandler.java:82-90`（switch 分发）、`:106-115`（handleLocation/handleChat）、`:158-166`、`:202-216`（`handleForward` 默认转发）、`service/PairService.java:212-238`（`forwardToPeer` 纯按 deviceId 查表）、`service/MessageStore.java:46-70/110-138/140-155`（落库直接用消息内 pairCode）
- **攻击路径**：
  1. 除 `task_*` 外，`chat/location/sos/typing/chat_read/chat_recall/media/默认类型` 均**不校验发送者 userId 是否属于消息 pairCode 对应的配对**；
  2. `resolvePairCode`（`EyeWebSocketHandler.java:211-218`）优先取消息自带 `pairCode`，缺失才回退服务端 `userToPair`——攻击者可指定任意 pairCode；
  3. `forwardToPeer(deviceId, msg)` 只按客户端声明的 `deviceId` 查 `deviceToPair` 路由到对端会话，**deviceId 与握手 userId 无绑定**；
  4. 结合 F-01 拿到他人 pairCode 后：可向该配对写入聊天行/假位置/SOS（`MessageStore` 落库成功，`userId` 字段为攻击者本人，但污染对方轨迹与聊天时间线）；`deviceId` 可从 `/api/pairs/me` 的 `peerProfile.deviceId`（`ProfileView.java:22`）或转发消息中获取，用于伪装身份消息。
- **可复现思路**：攻击者连 WS，发 `{"type":"location","deviceId":"<受害者deviceId>","pairCode":"<受害者code>","payload":{"lat":..,"lng":..},"timestamp":..}` → 服务端将攻击者坐标写入受害者配对轨迹并可转发给对端。
- **修复建议（P0，核心授权修复）**：以握手 userId 为唯一身份源，服务端解析/校验配对绑定，客户端 deviceId 仅作展示字段。
  ```java
  // 1) resolvePairCode 改为服务端权威：消息自带 pairCode 必须属于该用户
  private String resolvePairCode(WsMessage msg, long userId) {
      String self = pairService.getPairCodeOfUser(userId);
      if (self != null) return self;                       // 一律用自己的配对
      // 兼容历史：允许显式 pairCode，但必须 belongsToPair
      if (msg.getPairCode() != null
              && pairService.belongsToPair(userId, msg.getPairCode())) {
          return msg.getPairCode();
      }
      return null; // 无配对 → 上层拒绝该消息类型
  }
  // 2) 转发前校验发送者与目标 deviceId 同属一个配对
  public boolean canForwardFrom(long userId, String deviceId) {
      String pairOfUser = userToPair.get(userId);
      String pairOfDevice = deviceToPair.get(deviceId);
      return pairOfUser != null && pairOfUser.equals(pairOfDevice);
  }
  // 3) MessageStore.saveChat/saveLocation/saveSos 入口统一 belongsToPair 校验（或在 handler 前置校验）
  ```

### F-03 【High】WS 握手 token 走 URL query，明文落入 nginx 访问日志

- **位置**：`security/WsAuthInterceptor.java:38-42`（`request.getURI().getQuery()` 解析 `?token=`）；客户端 `android/.../WSClient.java:155`、`PrefsManager.java:123`（`url + "?token=" + token`）；`nginx/nginx.conf`（`location /ws/eye` 与 `location /` 均未自定义 access_log，默认 log_format 记录完整 `$request` 含 query）
- **攻击路径**：access token（8h 有效）明文出现在 nginx 访问日志、任何中间代理/网关日志、终端历史；日志一旦泄露（运维账号被控、日志外发、备份泄露）即全量会话劫持。
- **可复现思路**：`curl -k "https://<ECS>:18443/ws/eye?token=<JWT>"` 后查看 ECS 上 nginx access log 中该行含完整 JWT。
- **修复建议（P0）**：
  1. Android 端（OkHttp 支持自定义头）改为握手请求头传递，服务端从 `request.getHeaders()` 读取：
  ```java
  // WsAuthInterceptor.beforeHandshake 改读 Header
  List<String> auth = request.getHeaders().get("X-Auth-Token"); // 或 Sec-WebSocket-Protocol: token
  String token = (auth == null || auth.isEmpty()) ? null : auth.get(0);
  ```
  2. 若保留 query 方式（浏览器兼容），nginx 必须脱敏：
  ```nginx
  log_format wsauth escape=json '$remote_addr - [$time_local] "$request_method $uri $http_version" $status';
  access_log /var/log/nginx/access.log wsauth;   # 只记 $uri 不记 $request_uri（query 被剔除）
  ```
  3. 双管齐下：Header 传递为主 + nginx 日志永远不记录 query。

### F-04 【High】依赖版本滞后：Spring Boot 3.3.2（Spring Framework 6.1.11 / Tomcat 10.1.25）存在已公开 CVE

- **位置**：`pom.xml:8-11`（parent `spring-boot-starter-parent 3.3.2`）
- **风险**（经官方公告核验）：
  - CVE-2024-38816 / CVE-2024-38819：Spring Framework 6.1.0–6.1.11 路径遍历（WebMvc.fn/WebFlux.fn 函数式端点），**6.1.12 修复（Spring Boot 3.3.3 起）**；本工程为注解式 `@RestController`，实际利用面低，但扫描器必报；
  - CVE-2024-38820（DataBinder 大小写敏感匹配异常）6.1.14 修复；
  - Tomcat CVE-2024-50379 / CVE-2024-52316 / CVE-2024-56337：影响 10.1.x ≤ 10.1.33，10.1.34/10.1.35 修复；Boot 3.3.2 内嵌 Tomcat 10.1.25（需非默认配置方可利用，但属已知风险面）。
- **修复建议（P0，成本最低）**：parent 版本升级后重跑测试回归。
  ```xml
  <parent>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-parent</artifactId>
      <version>3.3.13</version>  <!-- 或当前最新 3.3.x / 3.5.x -->
      <relativePath/>
  </parent>
  ```
  附：`jjwt 0.11.5` 无已知严重 CVE，但建议随升级评估 0.12.x（API 兼容性小改）；`jackson 2.17.2` 建议随 Boot BOM 升级。

### F-05 【High】登录/注册无速率限制 + 密码策略过弱 → 在线爆破与账号枚举

- **位置**：`service/AuthService.java:47-48`（密码仅 ≥6 位）、`:76`（`encoder.matches`，无锁定）、`controller/AuthController.java`（login/register 无任何限流）；`GlobalExceptionHandler` 业务错误 HTTP 恒 200
- **攻击路径**：公网 18443 暴露 `/api/auth/login`；无 IP/账号维度限流；6 位密码空间小；`register` 对已存在用户名返回「用户名已存在」（400），可枚举有效账号；`login` 对不存在用户不执行 BCrypt（时间差可辅助枚举）。
- **修复建议（P0/P1）**：
  1. 登录限流：IP + username 双维度滑动窗口（如 5 次失败锁 15 分钟，返回统一文案）；
  2. 密码策略：≥8 位且含字母+数字（`register`/改密统一校验）；
  3. 注册接口对用户名占用返回与成功一致的提示（或统一「请求已受理」）；
  4. 不存在用户也执行一次假 BCrypt 以抹平时间差。
  ```java
  // 限流器（同 F-01 的滑动窗口模式，key = "login:ip:"+ip / "login:user:"+username）
  if (!loginLimiter.allowed(ipKey)) throw new BizException(429, "尝试过于频繁，请稍后再试");
  ```

### F-06 【Medium】聊天/位置/SOS 落库不校验 pairCode 归属（跨配对数据注入）

- **位置**：`service/MessageStore.java:46-70`（`saveChat`）、`:110-138`（`saveLocation`）、`:140-155`（`saveSos`）；上游 `handler/EyeWebSocketHandler.java:106-166`
- **攻击路径**：同 F-02 第 2/4 步——攻击者以自己账号向受害者配对码写入聊天行、假定位点（污染对方轨迹回放）、SOS 记录；`LocationThrottle` 按 pairCode 记账，攻击者还可消耗对方每日 1500 条配额（`LocationThrottle.java:44-46`）干扰正常轨迹采集。
- **修复建议**：见 F-02 示例代码第 3 步——`MessageStore` 三个写入方法前置 `belongsToPair` 校验（或 handler 统一在 `resolvePairCode` 后校验并拒绝非成员）。

### F-07 【Medium】chat_recall 可撤回对方消息（越权删除）

- **位置**：`service/MessageStore.java:85-107`（`recallChat` 仅按 `pairCode + ts` 定位，无 `fromUser` 校验）；`handler/EyeWebSocketHandler.java:144-155`
- **攻击路径**：配对内任一方可在 2 分钟窗口内构造 `{"type":"chat_recall","pairCode":"<code>","payload":{"msgTs":<对方消息ts>}}` 删除对方刚发的消息（`fromUser` 未比对）。
- **修复建议（P0）**：
  ```java
  public boolean recallChat(String pairCode, long msgTs, long operatorUserId) {
      ...
      ChatMessageEntity e = chatRepo.findByPairCodeAndTs(pairCode, msgTs);
      if (e == null) return false;
      if (e.getFromUser() == null || e.getFromUser() != operatorUserId) {
          log.warn("撤回失败：只能撤回自己的消息 pair={} ts={} op={}", pairCode, msgTs, operatorUserId);
          return false;
      }
      ...
  }
  ```

### F-08 【Medium】媒体上传无内容校验/配额 → 存储耗尽 DoS + 类型欺骗

- **位置**：`controller/MediaController.java:63-99`（upload：100MB/文件、无 per-user/pair 配额、`folderId` 不校验归属）、`service/MediaService.java:27/36-68/97-135`（扩展名白名单但**无 magic-byte 嗅探**、存储 `e.setMime(file.getContentType())` 保存客户端原始 Content-Type）、`controller/MediaController.java:151-152`（下载回放客户端 mime）
- **攻击路径**：
  1. 任意登录用户无限上传 100MB 文件 → 磁盘写满（`/data/media` 卷）→ 全站 DoS；
  2. 上传 HTML 伪装成 `.jpg`（Content-Type 报 `text/html`）→ 下载时以 `text/html` 回放；因下载需 Bearer 鉴权，浏览器直链 XSS 利用面低，但作为纵深防御应修；
  3. 恶意/非法内容经配对通道分发到对方设备（无扫描）。
- **修复建议（P1）**：
  1. 配额：按 pair 每日/每周上传字节数与文件数上限（内存计数 + DB 聚合）；
  2. magic-byte 嗅探：`Apache Tika` 或轻量头校验（JPEG `FF D8`、PNG `89 50 4E 47`、MP4 `ftyp` 等），与扩展名白名单交叉验证，不匹配即拒；
  3. 下载 Content-Type 由 `ext` 白名单推导（`MediaService.contentTypeOf(ext)`），不再回放客户端 mime；
  4. `folderId` 校验 `folderRepo.findById(folderId).getPairCode().equals(pairCode)`（对齐 `moveFolder` 的写法，`MediaController.java:206-215`）。
  ```java
  // 上传扩展名白名单 + magic byte 交叉校验示例
  byte[] head = Arrays.copyOf(bytes, Math.min(12, bytes.length));
  if ("jpg".equals(ext) && !(head[0] == (byte)0xFF && head[1] == (byte)0xD8)) throw new BizException(400, "文件内容与类型不符");
  if ("png".equals(ext) && !(head[0] == (byte)0x89 && head[1] == (byte)0x50)) throw new BizException(400, "文件内容与类型不符");
  ```

### F-09 【Medium】refresh token 无轮换/重用检测，30 天滑动有效

- **位置**：`service/AuthService.java:88-112`（refresh 仅校验 ver 后签发新对，不吊销旧 refresh）、`security/JwtUtil.java:39-47`
- **攻击路径**：refresh token 一旦泄露（设备端/日志/中间人），30 天内可持续换取新 access；受害者正常刷新不使旧 refresh 失效，多 token 并行有效；无设备绑定指纹。
- **修复建议（P1）**：
  1. refresh 时轮换：签发新 refresh 并记录旧 token 哈希（`refresh_token_hash` 列或 Redis），旧 refresh 立即失效；
  2. 重用检测：若提交已轮换过的旧 refresh → 判定泄露 → `ver+1` 吊销全族并踢 WS；
  3. 或保守方案：refresh TTL 缩短至 7 天 + 绑定 `deviceId` claim 校验。

### F-10 【Medium】WebSocket 无应用层频率/长度限制 → 资源耗尽

- **位置**：`handler/EyeWebSocketHandler.java:56-100`（`handleTextMessage` 无速率限制、无消息大小上限）；`service/MessageStore.java:46-70`（chat text 无长度/条数限制，2000 字列宽超长由 DB 异常吞掉但已转发）
- **攻击路径**：合法 token 连 WS 后以高频率发送 `chat`/`location`/`typing` → 对端被消息洪泛（内存/渲染 DoS）、DB 聊天表无界增长、`forwardToPeer` 放大流量。Tomcat 默认单帧 8KB（超限断连）已挡大帧，但频率无约束。
- **修复建议（P1）**：
  1. 每连接令牌桶（如 20 msg/s、突发 50）；
  2. `text` 长度校验（≤2000 字符，超长截断或拒绝）并限制单连接落库频次（如 60s 内 ≤60 条）；
  3. 未知消息类型（`handleForward` 默认分支，`:202-216`）改为白名单放行，避免未来新类型被盲目转发。

### F-11 【Medium】nginx 未配 client_max_body_size → 生产上传路径实际不可用（>1MB 即 413）

- **位置**：`nginx/nginx.conf`（`location /` 与 `location /ws/eye` 均未设置 `client_max_body_size`，nginx 默认 1MB；而 app 侧 `multipart.max-file-size=110MB`）
- **影响**：经公网 WSS 通道上传任何 >1MB 的照片/视频都会被 nginx 直接 413 拒绝，app 侧 110MB 限制形同虚设（功能缺陷 + 可用性风险）。同时这也是对外暴露面：若放开需配套 F-08 配额。
- **修复建议（P0，与 F-08 一起）**：
  ```nginx
  server {
      listen 18443 ssl;
      ...
      client_max_body_size 110m;        # 与后端 multipart 上限一致
      # 更细：location /api/media/upload { client_max_body_size 110m; }
  }
  ```

### F-12 【Medium】生产 `ddl-auto: update` + MySQL 连接 `useSSL=false`

- **位置**：`src/main/resources/application.yml:12-13`（`ddl-auto: update`）、`:7`（`useSSL=false&allowPublicKeyRetrieval=true`）
- **风险**：生产库表结构由 Hibernate 启动期自动变更，升级/回滚存在隐性 DDL 风险；DB 链路无 TLS（compose 内网桥接场景可接受，但本地直连 127.0.0.1:3306 与未来跨网部署需评估）。
- **修复建议（P1）**：`ddl-auto: validate` + Flyway 管理迁移脚本；DB 链路启用 TLS 或在网络边界（compose 内网 + 不发布 3306，现状已满足）明确声明并保持。

### F-13 【Low→Medium】日志记录敏感/隐私内容

- **位置**：`handler/EyeWebSocketHandler.java:65`（`log.warn("消息解析失败: ... raw={}", raw)` 记录消息全文，含聊天文本/位置/deviceId）、`service/PairService.java:104/120/131`（配对码+userId 入日志）、`security/WsAuthInterceptor.java:61`（握手失败仅记录异常消息，尚可）
- **风险**：日志泄露 = 聊天隐私泄露 + 配对码泄露（配合 F-01 可加速枚举命中）；`raw` 可能包含 JWT 或敏感 payload。
- **修复建议（P1）**：全文截断（`raw` 前 200 字符）、配对码脱敏（保留前 3 位 + `***`）、统一禁止记录 token/密码。
  ```java
  log.warn("消息解析失败: session={}, rawHead={}", session.getId(), truncate(raw, 200));
  ```

### F-14 【Low】Profile 暴露 deviceId 与完整资料

- **位置**：`util/ProfileView.java:22`（`deviceId` 入 profile）、`controller/PairController.java`（`me` 返回 peerProfile 全量：username/nickname/gender/birthday/bio/deviceId）
- **风险**：deviceId 是 WS 路由键（配合 F-02 扩大伪装面）；生日等为敏感个人信息，虽为情侣产品语义所必需，仍建议最小化。
- **修复建议（P2）**：profile 移除 `deviceId`（服务端内部使用即可）；`peerProfile` 仅返回产品必需字段；必要时对 `birthday` 降级为星座/年龄段。

### F-15 【Low】无安全响应头 + WS Origin 全开

- **位置**：`config/WebSocketConfig.java:32`（`setAllowedOrigins("*")`）；无 `X-Content-Type-Options`/`X-Frame-Options`/`Cache-Control` 等响应头
- **风险**：WS 因握手 token 鉴权，CSWSH 实际利用面低；但 Origin 收紧 + nosniff 是低成本纵深。
- **修复建议（P2）**：WS Origin 白名单（或保留 `*` 并注释说明依赖 token 鉴权）；新增响应头过滤器：
  ```java
  // OncePerRequestFilter 示例
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("X-Frame-Options", "DENY");
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("Referrer-Policy", "no-referrer");
  ```

### F-16 【Low】上传接口 `folderId` 未校验归属

- **位置**：`controller/MediaController.java:66,98`（upload 直接把 `folderId` 存入，无归属校验；`moveFolder` 有校验，见 `:206-215`）
- **影响**：媒体可被挂到任意 folderId（列表按 pair 过滤，仅造成分类错乱/前端展示异常），无数据泄露。
- **修复建议（P2）**：upload 复用 `moveFolder` 的 folder 归属校验逻辑。

### F-17 【Info】删除语义与备份、SOS 保留策略

- **位置**：`controller/PairController.java:75-93`（任一方成员可单方解除配对并硬删全部业务数据+磁盘媒体）；`service/DataCleanupService.java:27-50`（仅清理 30 天前的 chat/location，**SOS 记录无保留清理**）；`docs/ops-howto.md` 第 4 节（备份仅手工 docker tar）
- **风险/建议**：解除配对为不可逆硬删（无回收站/恢复路径），建议双向确认 + 二次校验码；SOS 记录纳入保留策略（与聊天一致 30 天或产品明示永久保留并合规声明）；备份自动化（cron + 异地）并定期演练恢复。

### F-18 【Info】业务错误恒 HTTP 200

- **位置**：`web/GlobalExceptionHandler.java`（BizException → HTTP 200 + body.code；唯一例外是 AuthInterceptor 的 401）
- **说明**：与客户端契约一致，非漏洞；但监控/网关无法按状态码过滤恶意流量，运维告警需解析 body.code；若未来接入 WAF/限流网关需注意。

### F-19 【Info】部署文档与代码不一致

- `deploy/tls-switchover.md` 通篇写 8443，实际 `nginx/nginx.conf` 与 `docker-compose.yml` 使用 **18443**（注释已说明 8443 被中游干扰换端口，文档未同步）；
- 需求背景称 MySQL 5.7，`docker-compose.yml` 实际为 `mysql:8.0`；
- 建议：同步更新文档，避免运维按旧文档开错安全组/端口。

### F-20 【Info】本地 `.env` 权限 644

- **位置**：`server/.env`（`-rw-r--r--`）；`.env.example` 已给出 `openssl rand -base64 48` 指引，`.gitignore:49` 已忽略 `server/.env`（核验：未被 git 跟踪，仅 `.env.example` 入库）
- **建议**：本地与 ECS 均 `chmod 600 .env`；部署主机上限制仅运维账号可读。

---

## 3. 加固优先级矩阵（按 风险 × 成本 排序）

### P0 —— 立即可修（本周，低成本高收益）
> **状态（2026-10-02）：全部 7 项已实现**——①Boot 3.3.13 ✓ ②WS 身份绑定 ✓ ③token Header+nginx 脱敏 ✓（客户端切 Header 属 P2 阶段）④配对限流+过期 ✓ ⑤撤回归属 ✓ ⑥nginx body size ✓ ⑦登录限流+密码策略 ✓；冒烟验证：弱密码 400/强密码成功/6 次失败锁 429。

| 优先级 | 发现 | 成本 | 收益 | 落地方式 |
|---|---|---|---|---|
| P0-1 | F-04 升级 Spring Boot 3.3.2 → 3.3.13+ | 极低（pom 一行 + 回归） | 消除已公开 CVE 扫描面 | pom.xml parent 版本 |
| P0-2 | F-02/F-06 WS 发送者-配对绑定校验 | 中（改 handler+PairService+MessageStore） | 封堵跨配对数据注入/身份伪装核心链路 | 见 F-02 示例代码 |
| P0-3 | F-03 WS token 改 Header + nginx 日志脱敏 | 低（双端各一处 + nginx 两行） | 消除 token 日志泄露 | 见 F-03 示例代码 |
| P0-4 | F-01 配对码加入限流 + PENDING 过期 | 低 | 封堵 1e6 暴力枚举劫持 | 见 F-01 示例代码 |
| P0-5 | F-07 撤回归属校验 | 极低（一个方法加参数） | 封堵越权删除 | 见 F-07 示例代码 |
| P0-6 | F-11 nginx client_max_body_size | 极低（两行） | 恢复生产上传功能 | 见 F-11 示例代码 |
| P0-7 | F-05 登录限流 + 密码 ≥8 位 | 低 | 封堵在线爆破 | 见 F-05 示例代码 |

### P1 —— 短中期（2–4 周）
> **状态（2026-10-02）**：P1-5 日志脱敏 ✓（raw 截断 200 字符 + 配对码掩码）；P1-2 WS 频率限流 ✓（60 条/10s 断开）+ 文本 ≤2000 字 ✓；P1-1 上传加固 ✓（magic-byte 嗅探 + 配对日配额 200MB/500 文件 + 下载 mime 白名单推导）；P1-3 refresh 轮换 ✓（jti 唯一化 + DB 持久化 SHA-256 哈希 + 重用检测吊销全族，实测轮换/吊销通过）；P1-6 SOS 保留 ✓（30 天清理）。**P1-4（Flyway）与 P1-1 多实例配额 Redis 化延后**（demo 阶段 ddl-auto update 够用，文档记录）。

| 优先级 | 发现 | 成本 | 收益 |
|---|---|---|---|
| P1-1 | F-08 上传配额 + magic-byte 嗅探 + mime 白名单回放 | 中 | 防存储耗尽 DoS、类型欺骗 |
| P1-2 | F-10 WS 频率限流 + text 长度/条数限制 | 中 | 防消息洪泛、DB 无界增长 |
| P1-3 | F-09 refresh 轮换 + 重用检测 | 中 | 防 refresh token 长期滥用 |
| P1-4 | F-12 ddl-auto validate + Flyway | 中 | 生产 Schema 变更可控 |
| P1-5 | F-13 日志脱敏（raw 截断、配对码脱敏） | 低 | 防隐私/配对码经日志泄露 |
| P1-6 | F-17 SOS 保留策略 + 备份自动化 | 低-中 | 合规 + 数据可用性 |

### P2 —— 长期/产品迭代

| 优先级 | 发现 | 成本 | 收益 |
|---|---|---|---|
| P2-1 | F-15 安全响应头 + WS Origin 白名单 | 低 | 纵深防御 |
| P2-2 | F-14 deviceId/profile 最小化 | 低 | 隐私合规 + 缩小伪装面 |
| P2-3 | F-16 upload folderId 归属校验 | 低 | 数据一致性 |
| P2-4 | 解除配对双向确认、账号注销 API、数据导出 | 中 | GB/T 35273 合规（见 §5） |
| P2-5 | 多实例部署：WS 会话/限流状态 Redis 化 | 高 | 架构演进前提 |
| P2-6 | F-19/F-20 文档同步、.env 权限加固 | 低 | 运维安全 |

---

## 4. 验收方法（如何验证修复）

### 4.1 自动化 / 命令行复测清单

```bash
# 1) 登录限流（F-05）：连续错误登录，观察第 6 次起被 429/统一文案拦截
for i in $(seq 1 10); do curl -sk -o /dev/null -w "%{http_code}\n" \
  -X POST https://<ECS>:18443/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"nobody","password":"wrong123"}'; done

# 2) 配对码枚举限流（F-01）：高频 pair_request 触发限流/锁码
#    （WS 侧建议用脚本连 /ws/eye 发 20 次无效 pair_request，观察第 11 次起拒绝）

# 3) 越权访问（F-02/F-06）：非成员用户访问他人配对数据应 403
curl -sk -H "Authorization: Bearer $TOKEN_C" \
  https://<ECS>:18443/api/chats/<victimCode>        # 期望 body.code=403

# 4) 撤回他人消息（F-07）：B 撤回 A 刚发的消息 → 期望失败提示
# 5) 上传 >1MB（F-11）：经公网 WSS 通道上传 2MB 文件 → 期望 200 而非 413
curl -sk -H "Authorization: Bearer $TOKEN" \
  -F "file=@2MB.jpg" -F "pairCode=$CODE" \
  https://<ECS>:18443/api/media/upload

# 6) token 传递（F-03）：新客户端握手不再带 query，nginx access log 无 token
grep -c "ws/eye?token=" /var/log/nginx/access.log   # 期望 0

# 7) 依赖扫描（F-04）：升级后 CVE 清零
mvn dependency-check:check   # 或 trivy image --severity HIGH
```

### 4.2 手动复测项

- **WS 身份绑定**：用账号 C（非配对成员）向配对 P 的 deviceId 发 `chat`/`location` → 服务端拒绝且不落库、不转发；
- **配对码过期**：创建 PENDING 配对后等待过期窗口 → 原码再 join 应失败；
- **refresh 轮换**：连续两次用同一 refresh 换新 → 第二次应被拒并触发全族吊销；
- **上传类型欺骗**：`Content-Type: text/html` + `.jpg` 扩展名上传 → 拒绝；正常 jpg 上传 → 下载响应头 Content-Type 为 `image/jpeg`（由白名单推导）；
- **解除配对**：二次确认生效，数据删除后媒体卷无残留文件；
- **回归**：`mvn test`（ProdCredentialValidator/WsMessage/LocationThrottle 单测）+ 双端配对全流程冒烟（pair_request → confirm → chat → 位置 → 任务状态机 → 媒体上传/下载/删除）。

### 4.3 工具扫描

- OWASP ZAP baseline 扫 `/api`（登录态导入 token）核对 F-02/F-05/F-08 类问题；
- Trivy / Grype 扫镜像核对 F-04 依赖 CVE；
- 日志巡检：确认无 token/密码/完整聊天文本入日志（F-03/F-13）。

---

## 5. 合规注意

### 5.1 GB/T 35273《个人信息安全规范》对照

| 条款要点 | 现状 | 差距与建议 |
|---|---|---|
| 最小必要收集 | 收集 username/password/nickname/gender/birthday/bio/设备标识/位置/聊天/媒体 | 基本符合产品必需；`birthday` 建议可降级展示（F-14） |
| 告知同意 | 无隐私政策说明（仓库内未见） | 需补充《隐私政策》并在客户端首次启动明示同意，位置为敏感个人信息须单独授权 |
| 删除权/账号注销 | 无账号注销 API；解除配对可硬删配对数据 | P2-4：新增注销接口（删用户+配对+业务数据+媒体），明确删除范围与时限 |
| 保存期限 | 聊天/轨迹 30 天自动清理（DataCleanupService）；媒体永久；SOS 无清理 | F-17：SOS 纳入保留策略；媒体永久保留需在隐私政策中明示并给用户删除入口 |
| 委托处理/安全措施 | TLS 传输、BCrypt、最小权限 DB 账号 | 建议补充数据泄露应急预案与最小权限复核（DB 账号仅 eye_monitor 库，已由文档声明） |
| 儿童/特殊人群 | 情侣应用无年龄门禁 | 建议注册时增加年龄声明或适用对象说明 |

### 5.2 等保 2.0（二级参考）

- **身份鉴别**：双因素为可选增强；当前 JWT 无设备绑定（F-09 关联），建议至少 refresh 轮换 + 设备指纹；
- **访问控制**：REST 侧 belongsToPair 覆盖较好；WS 侧身份绑定缺失（F-02）为等保「访问控制/越权」直接扣分项，优先修复；
- **安全审计**：登录/登出/解除配对/删除媒体等敏感操作无审计日志 → 建议增加操作审计表（P2），日志留存 ≥6 个月（当前 nginx 日志默认留存策略未声明）；
- **入侵防范**：登录限流（F-05）、WS 频率限流（F-10）为等保「入侵防范」要求项；
- **数据完整性/保密性**：传输层 TLS（现状 OK）；存储加密可选（MySQL 落盘加密评估）；备份恢复策略需自动化与演练（F-17）；
- **边界防护**：安全组 22+18443、3306 不发布、8080 仅 127.0.0.1——现状良好，维持并文档化。

---

## 附：做得好的方面（保留项）

- `ProdCredentialValidator` 启动强校验：拒绝缺失/历史默认/root/弱 JWT secret（`config/ProdCredentialValidator.java`）；
- 凭据全部环境变量注入，`.env` 已 gitignore 且未被跟踪（`.gitignore:49`）；
- 容器以非 root（uid 10001）运行、8080 仅绑 127.0.0.1、MySQL 3306 不发布（`Dockerfile`/`docker-compose.yml`）；
- BCrypt 哈希、JWT ver 版本号吊销（登录/登出全族失效）、WS 单会话踢下线（`WsSessionManager`）；
- REST 媒体下载/删除/移动、聊天/轨迹/任务/纪念日均做 `belongsToPair` 归属校验；
- 无 SQL 拼接注入面（唯一 `@Query` 为参数化 JPQL）、`sanitize()` 防路径穿越、扩展名白名单；
- 位置落库限流（`LocationThrottle`：精度/去重/时距/每日上限）、30 天数据保留清理；
- 统一异常处理不向客户端泄露堆栈；`open-in-view: false`。
