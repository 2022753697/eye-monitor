package com.eyemonitor.service;

import android.content.Context;
import android.util.Log;

import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AnniversaryCacheEntity;
import com.eyemonitor.db.AppNameCacheEntity;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.AffectionCacheEntity;
import com.eyemonitor.db.AffectionStateHolder;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.db.FenceCacheEntity;
import com.eyemonitor.db.FolderCacheEntity;
import com.eyemonitor.db.LocationCacheEntity;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.util.AppNameResolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 服务器 -> 本地 Room 缓存同步（组件 7 全量迁移：服务器为真源，本机为离线缓存）。
 * <p>
 * 触发时机：登录成功 / WS 重连成功后（syncAll）。
 * 增量通道：收到的 anniversary_sync / fence_sync / media / media_deleted 即时更新缓存。
 */
public final class SyncManager {

    private static final String TAG = "SyncManager";

    private SyncManager() {}

    /** 登录/重连后全量拉取配对相关的 聊天/纪念日/围栏 到本地缓存（失败静默，等待下轮触发） */
    public static void syncAll(Context context) {
        PrefsManager prefs = new PrefsManager(context);
        // 应用名映射是全局数据，不依赖配对，先同步（WS 连上即可刷新）
        syncAppNames(context);
        String pairCode = prefs.getPairCode();
        if (!prefs.isLoggedIn() || pairCode == null || pairCode.isEmpty()) {
            Log.d(TAG, "未登录或未配对，跳过配对数据同步");
            return;
        }
        syncAnniversaries(context);
        syncFences(context);
        syncFolders(context);
        syncChats(context);
        syncMediaMeta(context);
        syncTasks(context);
        pruneLocalCaches(context);
    }

    /** 任务对账（离线补收/换机恢复）：GET /api/tasks 拉全量，本地 upsert + 补齐聊天气泡行 */
    public static void syncTasks(Context context) {
        String pairCode = new PrefsManager(context).getPairCode();
        if (pairCode == null) return;
        AppDatabase db = AppDatabase.getInstance(context);
        AuthManager.i(context).getElement(context, "/api/tasks/" + pairCode,
                new AuthManager.ElementCallback() {
                    @Override
                    public void onSuccess(JsonElement data) {
                        if (data == null || !data.isJsonArray()) return;
                        JsonArray arr = data.getAsJsonArray();
                        AppDatabase.dbExecutor.execute(() -> {
                            int n = 0;
                            for (int i = 0; i < arr.size(); i++) {
                                JsonObject o = arr.get(i).getAsJsonObject();
                                String taskId = o.has("taskId") ? o.get("taskId").getAsString() : null;
                                if (taskId == null || taskId.isEmpty()) continue;
                                String status = o.has("status") ? o.get("status").getAsString() : null;
                                boolean isMine = o.has("isMine") && !o.get("isMine").isJsonNull()
                                        && o.get("isMine").getAsBoolean();
                                long ts = o.has("ts") && !o.get("ts").isJsonNull()
                                        ? o.get("ts").getAsLong() : System.currentTimeMillis();
                                com.eyemonitor.db.TaskEntity e = new com.eyemonitor.db.TaskEntity(
                                        taskId,
                                        str(o, "content"),
                                        str(o, "mediaFileId"),
                                        str(o, "rewardType"),
                                        str(o, "rewardText"),
                                        str(o, "peerName"),
                                        isMine,
                                        status == null ? com.eyemonitor.db.TaskEntity.STATUS_PENDING : status,
                                        str(o, "reason"),
                                        ts);
                                e.mediaFileIds = str(o, "mediaFileIds");
                                if (o.has("completedTs") && !o.get("completedTs").isJsonNull()) {
                                    e.completedTs = o.get("completedTs").getAsLong();
                                }
                                if (o.has("rewardedTs") && !o.get("rewardedTs").isJsonNull()) {
                                    e.rewardedTs = o.get("rewardedTs").getAsLong();
                                }
                                db.taskDao().upsert(e);
                                // 聊天气泡行补齐（(kind,ts,text) 幂等，离线补收可见）
                                if (db.chatDao().countByKindTsText("task", ts, taskId) == 0) {
                                    db.chatDao().insert(new com.eyemonitor.db.ChatEntity(
                                            "task", taskId, isMine ? null : e.peerName, isMine, ts));
                                }
                                n++;
                            }
                            Log.i(TAG, "任务同步: " + n + " 条");
                            if (n > 0) {
                                broadcastChatReload(context);
                            }
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        Log.w(TAG, "任务同步错误: " + code + " " + msg);
                    }
                });
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    /** 同步媒体元数据（fileId/mime/duration），离线补收的媒体消息渲染与自动下载依赖它 */
    private static void syncMediaMeta(Context context) {
        String pairCode = new PrefsManager(context).getPairCode();
        if (pairCode == null) return;
        AppDatabase db = AppDatabase.getInstance(context);
        AuthManager.i(context).getElement(context, "/api/media",
                new AuthManager.ElementCallback() {
                    @Override
                    public void onSuccess(JsonElement data) {
                        if (data == null || !data.isJsonArray()) return;
                        JsonArray arr = data.getAsJsonArray();
                        AppDatabase.dbExecutor.execute(() -> {
                            java.util.Set<String> serverIds = new java.util.HashSet<>();
                            int n = 0;
                            for (int i = 0; i < arr.size(); i++) {
                                JsonObject o = arr.get(i).getAsJsonObject();
                                String fileId = o.has("fileId") ? o.get("fileId").getAsString() : null;
                                if (fileId == null || fileId.isEmpty()) continue;
                                serverIds.add(fileId);
                                com.eyemonitor.db.MediaCacheEntity m = new com.eyemonitor.db.MediaCacheEntity();
                                m.fileId = fileId;
                                String mime = o.has("mime") && !o.get("mime").isJsonNull()
                                        ? o.get("mime").getAsString() : null;
                                String serverFile = o.has("fileName") && !o.get("fileName").isJsonNull()
                                        ? o.get("fileName").getAsString() : null;
                                // 旧数据/上传时丢失 Content-Type 的行 mime 为 octet-stream：按文件名兜底推断
                                if (mime == null || "application/octet-stream".equals(mime)) {
                                    mime = com.eyemonitor.util.MediaUtils.inferMime(null, serverFile);
                                }
                                m.mime = mime;
                                m.size = o.has("size") ? o.get("size").getAsLong() : 0L;
                                m.duration = o.has("duration") && !o.get("duration").isJsonNull()
                                        ? o.get("duration").getAsLong() : 0L;
                                m.serverFileName = serverFile;
                                upsertPreservingLocal(db, m);
                                n++;
                            }
                            Log.i(TAG, "媒体元数据同步: " + n + " 条");
                            // 对账清理：服务端已不存在的本地缓存行删掉（防图库幽灵图）
                            if (serverIds.isEmpty()) {
                                db.cacheDao().clearMedia();
                            } else {
                                db.cacheDao().deleteMediaNotIn(new java.util.ArrayList<>(serverIds));
                            }
                            if (n > 0) {
                                broadcastChatReload(context);
                            }
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        Log.w(TAG, "媒体元数据同步错误: " + code + " " + msg);
                    }
                });
    }

    /** 广播 sync_chat_done：聊天页从 Room 全量重载（历史/媒体气泡显示） */
    private static void broadcastChatReload(Context context) {
        try {
            android.content.Intent i = new android.content.Intent(MonitorService.ACTION_EVENT);
            i.putExtra(MonitorService.EXTRA_EVENT_JSON,
                    "{\"type\":\"sync_chat_done\",\"deviceId\":\"\",\"pairCode\":\"\","
                            + "\"payload\":{},\"timestamp\":" + System.currentTimeMillis() + "}");
            context.sendBroadcast(i);
        } catch (Exception e) {
            Log.e(TAG, "广播聊天刷新失败", e);
        }
    }

    /** 30 天保留清理（与服务器策略一致；媒体缓存不清理——媒体永久保留） */
    public static void pruneLocalCaches(Context context) {
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        AppDatabase db = AppDatabase.getInstance(context);
        AppDatabase.dbExecutor.execute(() -> {
            db.cacheDao().deleteLocationsBefore(cutoff);
            db.chatDao().deleteBefore(cutoff);
            Log.d(TAG, "本地缓存 30 天保留清理完成");
        });
    }

    /** 同步应用名映射（GET /api/app-names）：写 Room 缓存 + 刷新 AppNameResolver 内存表 */
    public static void syncAppNames(Context context) {
        AuthManager.i(context).getElement(context, "/api/app-names", new AuthManager.ElementCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonElement data) {
                if (data == null || !data.isJsonArray()) {
                    Log.w(TAG, "app-names 响应非数组，跳过");
                    return;
                }
                JsonArray arr = data.getAsJsonArray();
                final java.util.List<AppNameCacheEntity> list = new java.util.ArrayList<>();
                final java.util.Map<String, String> map = new java.util.HashMap<>();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    String pkg = o.has("packageName") && !o.get("packageName").isJsonNull()
                            ? o.get("packageName").getAsString() : null;
                    String name = o.has("appName") && !o.get("appName").isJsonNull()
                            ? o.get("appName").getAsString() : null;
                    if (pkg != null && !pkg.isEmpty() && name != null) {
                        list.add(new AppNameCacheEntity(pkg, name));
                        map.put(pkg, name);
                    }
                }
                AppDatabase db = AppDatabase.getInstance(context);
                AppDatabase.dbExecutor.execute(() -> {
                    if (!list.isEmpty()) {
                        db.cacheDao().upsertAppNames(list);
                    }
                    // 内存表即时生效（App 切换事件展示用）
                    AppNameResolver.updateFromCache(map);
                    Log.d(TAG, "应用名映射同步完成: " + map.size() + " 条");
                });
            }

            @Override
            public void onError(int code, String msg) {
                Log.d(TAG, "app-names 同步失败: " + code + " " + msg);
            }
        });
    }

    /** 处理服务器推送的缓存级消息，保持本地缓存与服务器一致 */
    public static void handleWsMessage(Context context, WsMessage message) {
        switch (message.getType()) {
            case "anniversary_sync":
                handleAnniversarySync(context, message);
                break;
            case "fence_sync":
                handleFenceSync(context, message);
                break;
            case "folder_sync":
                handleFolderSync(context, message);
                break;
            case "media":
                handleMediaUpsert(context, message);
                break;
            case "media_deleted":
                handleMediaDelete(context, message);
                break;
            case "affection_sync":
                // 亲密度/等级快照：更新缓存 + 内存镜像（广播由 MonitorService 统一发出）
                handleAffectionSync(context, message);
                break;
            default:
                break;
        }
    }

    /** 亲密度快照落库（affection_cache 单行 upsert + 内存镜像刷新；Room 走 dbExecutor） */
    private static void handleAffectionSync(Context context, WsMessage message) {
        java.util.Map<String, Object> p = message.getPayload();
        if (p == null) return;
        final AffectionCacheEntity e = new AffectionCacheEntity();
        e.points = p.get("points") instanceof Number ? ((Number) p.get("points")).intValue() : 0;
        e.level = p.get("level") instanceof Number ? ((Number) p.get("level")).intValue() : 0;
        e.progress = p.get("progress") instanceof Number
                ? ((Number) p.get("progress")).doubleValue() : 0d;
        e.title = p.get("title") instanceof String ? (String) p.get("title") : "";
        e.updatedAt = p.get("updatedAt") instanceof Number
                ? ((Number) p.get("updatedAt")).longValue() : System.currentTimeMillis();
        AppDatabase db = AppDatabase.getInstance(context);
        AppDatabase.dbExecutor.execute(() -> {
            db.cacheDao().upsertAffection(e);
            AffectionStateHolder.update(e);
            Log.i(TAG, "亲密度缓存已更新: points=" + e.points + ", level=" + e.level);
        });
    }

    /** 纪念日全量拉取到 Room 缓存（AnniversaryActivity 变更成功后主动刷新） */
    public static void syncAnniversaries(Context context) {
        AuthManager.i(context).get(context, "/api/anniversaries", new AuthManager.Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                try {
                    JsonArray arr = data.getAsJsonArray("anniversaries");
                    if (arr == null) return;
                    java.util.List<AnniversaryCacheEntity> list = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        AnniversaryCacheEntity e = new AnniversaryCacheEntity();
                        e.serverId = o.get("id").getAsLong();
                        e.name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "";
                        e.date = o.has("date") && !o.get("date").isJsonNull() ? o.get("date").getAsString() : "";
                        e.repeat = o.has("repeat") && o.get("repeat").getAsBoolean();
                        e.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : System.currentTimeMillis();
                        list.add(e);
                    }
                    AppDatabase db = AppDatabase.getInstance(context);
                    AppDatabase.dbExecutor.execute(() -> {
                        db.cacheDao().clearAnniversaries();
                        if (!list.isEmpty()) db.cacheDao().upsertAnniversaries(list);
                        Log.i(TAG, "纪念日缓存已同步: " + list.size() + " 条");
                    });
                } catch (Exception e) {
                    Log.e(TAG, "同步纪念日失败", e);
                }
            }

            @Override
            public void onError(int code, String msg) {
                Log.w(TAG, "同步纪念日错误: " + code + " " + msg);
            }
        });
    }

    private static void syncFences(Context context) {
        AuthManager.i(context).get(context, "/api/fences", new AuthManager.Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                try {
                    JsonArray arr = data.getAsJsonArray("fences");
                    if (arr == null) return;
                    java.util.List<FenceCacheEntity> list = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        FenceCacheEntity e = new FenceCacheEntity();
                        e.serverId = o.get("id").getAsLong();
                        e.name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "";
                        e.lat = o.get("lat").getAsDouble();
                        e.lng = o.get("lng").getAsDouble();
                        e.radius = o.get("radius").getAsDouble();
                        e.enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
                        list.add(e);
                    }
                    AppDatabase db = AppDatabase.getInstance(context);
                    AppDatabase.dbExecutor.execute(() -> {
                        db.cacheDao().clearFences();
                        if (!list.isEmpty()) db.cacheDao().upsertFences(list);
                        Log.i(TAG, "围栏缓存已同步: " + list.size() + " 条");
                    });
                } catch (Exception e) {
                    Log.e(TAG, "同步围栏失败", e);
                }
            }

            @Override
            public void onError(int code, String msg) {
                Log.w(TAG, "同步围栏错误: " + code + " " + msg);
            }
        });
    }

    /** 增量拉取聊天历史（afterTs = 本地最大时间戳），避免重复插入 */
    private static void syncChats(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        AppDatabase.dbExecutor.execute(() -> {
            // 修复：增量游标用「对方消息」最大 ts——自己的消息不推进游标，
            // 否则离线补发被“对端不在线跳过转发”后，游标被自己的新消息推过，漏掉未收到的那条
            long afterTs = db.cacheDao().getMaxPeerChatTs();
            final String pairCode = new PrefsManager(context).getPairCode();
            if (pairCode == null) return;
            // 注意：/api/chats 返回 {code,data:[...]}（data 为数组），必须用 getElement（Callback 只接受对象型 data）
            AuthManager.i(context).getElement(context,
                    "/api/chats/" + pairCode + "?afterTs=" + afterTs,
                    new AuthManager.ElementCallback() {
                        @Override
                        public void onSuccess(JsonElement data) {
                            if (data == null || !data.isJsonArray()) return;
                            JsonArray arr = data.getAsJsonArray();
                            java.util.List<ChatEntity> list = new java.util.ArrayList<>();
                            for (int i = 0; i < arr.size(); i++) {
                                JsonObject o = arr.get(i).getAsJsonObject();
                                long ts = o.has("ts") ? o.get("ts").getAsLong() : System.currentTimeMillis();
                                String text = o.has("text") ? o.get("text").getAsString() : "";
                                boolean isSystem = o.has("isSystem") && o.get("isSystem").getAsBoolean();
                                String kind = o.has("kind") && !o.get("kind").isJsonNull()
                                        ? o.get("kind").getAsString() : null;
                                String localKind = isSystem ? "system"
                                        : ("media".equals(kind) ? "media" : "chat");
                                // 服务器 fromUser 为账号 ID，本地暂无用户 ID 映射：新增行按 peer 渲染。
                                // 自自身消息本地在发送时已插入（isSelf=true），靠下方去重直接跳过，不会被错标为对方。
                                list.add(new ChatEntity(localKind,
                                        text, null, false, ts));
                            }
                            if (list.isEmpty()) return;
                            AppDatabase.dbExecutor.execute(() -> {
                                for (ChatEntity e : list) {
                                    // 去重：本地已有相同 (kind, ts, text) 则跳过——
                                    // 否则自己发的媒体/文本会在重进 app 拉历史时被回放成重复的「对方身份」气泡
                                    if (db.chatDao().countByKindTsText(
                                            e.kind, e.timestamp, e.text) > 0) {
                                        continue;
                                    }
                                    db.chatDao().insert(e);
                                }
                                Log.i(TAG, "聊天历史增量同步: " + list.size() + " 条 (afterTs=" + afterTs + ")");
                                // 通知聊天页从 Room 重载（离线消息显示的关键一步）
                                broadcastChatReload(context);
                            });
                        }

                        @Override
                        public void onError(int code, String msg) {
                            Log.w(TAG, "同步聊天错误: " + code + " " + msg);
                        }
                    });
        });
    }

    private static void handleAnniversarySync(Context context, WsMessage message) {
        java.util.Map<String, Object> p = message.getPayload();
        if (p == null) return;
        Object id = p.get("id");
        if (!(id instanceof Number)) return;
        long serverId = ((Number) id).longValue();
        Object action = p.get("action");
        AppDatabase db = AppDatabase.getInstance(context);
        if (action instanceof String && "delete".equals(action)) {
            AppDatabase.dbExecutor.execute(() -> db.cacheDao().deleteAnniversary(serverId));
            return;
        }
        AnniversaryCacheEntity e = new AnniversaryCacheEntity();
        e.serverId = serverId;
        e.updatedAt = p.get("updatedAt") instanceof Number
                ? ((Number) p.get("updatedAt")).longValue() : System.currentTimeMillis();
        e.name = p.get("name") instanceof String ? (String) p.get("name") : "";
        e.date = p.get("date") instanceof String ? (String) p.get("date") : "";
        e.repeat = p.get("repeat") instanceof Boolean && (Boolean) p.get("repeat");
        AppDatabase.dbExecutor.execute(() -> db.cacheDao().upsertAnniversary(e));
    }

    /** 图库文件夹全量拉取到 Room 缓存（创建/删除后也可主动调用） */
    public static void syncFolders(Context context) {
        AuthManager.i(context).get(context, "/api/folders", new AuthManager.Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                try {
                    JsonArray arr = data.getAsJsonArray("folders");
                    if (arr == null) return;
                    java.util.List<FolderCacheEntity> list = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        FolderCacheEntity e = new FolderCacheEntity();
                        e.id = o.get("id").getAsLong();
                        e.name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "";
                        e.ts = o.has("createdAt") ? o.get("createdAt").getAsLong() : System.currentTimeMillis();
                        list.add(e);
                    }
                    AppDatabase db = AppDatabase.getInstance(context);
                    AppDatabase.dbExecutor.execute(() -> {
                        db.cacheDao().clearFolders();
                        if (!list.isEmpty()) db.cacheDao().upsertFolders(list);
                        Log.i(TAG, "文件夹缓存已同步: " + list.size() + " 个");
                    });
                } catch (Exception e) {
                    Log.e(TAG, "同步文件夹失败", e);
                }
            }

            @Override
            public void onError(int code, String msg) {
                Log.w(TAG, "同步文件夹错误: " + code + " " + msg);
            }
        });
    }

    private static void handleFolderSync(Context context, WsMessage message) {
        java.util.Map<String, Object> p = message.getPayload();
        if (p == null) return;
        Object id = p.get("id");
        if (!(id instanceof Number)) return;
        long serverId = ((Number) id).longValue();
        Object action = p.get("action");
        AppDatabase db = AppDatabase.getInstance(context);
        if (action instanceof String && "delete".equals(action)) {
            AppDatabase.dbExecutor.execute(() -> db.cacheDao().deleteFolder(serverId));
            return;
        }
        FolderCacheEntity e = new FolderCacheEntity();
        e.id = serverId;
        e.name = p.get("name") instanceof String ? (String) p.get("name") : "";
        e.ts = System.currentTimeMillis();
        AppDatabase.dbExecutor.execute(() -> db.cacheDao().upsertFolder(e));
    }

    private static void handleFenceSync(Context context, WsMessage message) {
        java.util.Map<String, Object> p = message.getPayload();
        if (p == null) return;
        Object id = p.get("id");
        if (!(id instanceof Number)) return;
        long serverId = ((Number) id).longValue();
        Object action = p.get("action");
        AppDatabase db = AppDatabase.getInstance(context);
        if (action instanceof String && "delete".equals(action)) {
            AppDatabase.dbExecutor.execute(() -> db.cacheDao().deleteFence(serverId));
            return;
        }
        FenceCacheEntity e = new FenceCacheEntity();
        e.serverId = serverId;
        e.name = p.get("name") instanceof String ? (String) p.get("name") : "";
        e.lat = p.get("lat") instanceof Number ? ((Number) p.get("lat")).doubleValue() : 0;
        e.lng = p.get("lng") instanceof Number ? ((Number) p.get("lng")).doubleValue() : 0;
        e.radius = p.get("radius") instanceof Number ? ((Number) p.get("radius")).doubleValue() : 0;
        e.enabled = !(p.get("enabled") instanceof Boolean) || (Boolean) p.get("enabled");
        AppDatabase.dbExecutor.execute(() -> db.cacheDao().upsertFence(e));
    }

    private static void handleMediaUpsert(Context context, WsMessage message) {
        java.util.Map<String, Object> p = message.getPayload();
        if (p == null) return;
        Object fileId = p.get("fileId");
        if (!(fileId instanceof String) || ((String) fileId).isEmpty()) return;
        MediaCacheEntity e = new MediaCacheEntity();
        e.fileId = (String) fileId;
        e.serverFileName = p.get("fileName") instanceof String ? (String) p.get("fileName") : null;
        String mime = p.get("mime") instanceof String ? (String) p.get("mime") : null;
        if (mime == null || "application/octet-stream".equals(mime)) {
            mime = com.eyemonitor.util.MediaUtils.inferMime(null, e.serverFileName);
        }
        e.mime = mime;
        e.size = p.get("size") instanceof Number ? ((Number) p.get("size")).longValue() : 0;
        e.duration = p.get("duration") instanceof Number ? ((Number) p.get("duration")).longValue() : 0;
        if (p.get("folderId") instanceof Number) {
            e.folderId = ((Number) p.get("folderId")).longValue();
        }
        e.ts = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        AppDatabase db = AppDatabase.getInstance(context);
        AppDatabase.dbExecutor.execute(() -> upsertPreservingLocal(db, e));
    }

    /** upsert 但保留本地字段（waveform/localPath）：服务端同步不持有这些，统一在此合并防覆盖 */
    private static void upsertPreservingLocal(AppDatabase db, MediaCacheEntity incoming) {
        if (incoming != null && incoming.fileId != null) {
            MediaCacheEntity existing = db.cacheDao().getMedia(incoming.fileId);
            if (existing != null) {
                if (incoming.waveform == null || incoming.waveform.isEmpty()) {
                    incoming.waveform = existing.waveform;   // 语音波形（纯本地，防被服务端同步抹掉）
                }
                if (incoming.localPath == null || incoming.localPath.isEmpty()) {
                    incoming.localPath = existing.localPath;
                }
            }
        }
        db.cacheDao().upsertMedia(incoming);
    }

    private static void handleMediaDelete(Context context, WsMessage message) {
        java.util.Map<String, Object> p = message.getPayload();
        if (p == null) return;
        Object fileId = p.get("fileId");
        if (!(fileId instanceof String)) return;
        AppDatabase db = AppDatabase.getInstance(context);
        AppDatabase.dbExecutor.execute(() -> db.cacheDao().deleteMedia((String) fileId));
    }

    /** 供轨迹回放读取时间段缓存（Wave-2 直接使用 cacheDao().getLocations(start,end)） */
}