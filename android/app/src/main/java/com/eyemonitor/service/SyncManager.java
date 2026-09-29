package com.eyemonitor.service;

import android.content.Context;
import android.util.Log;

import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AnniversaryCacheEntity;
import com.eyemonitor.db.AppNameCacheEntity;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.db.FenceCacheEntity;
import com.eyemonitor.db.LocationCacheEntity;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.util.AppNameResolver;

import com.google.gson.JsonArray;
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
        syncChats(context);
        pruneLocalCaches(context);
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
            case "media":
                handleMediaUpsert(context, message);
                break;
            case "media_deleted":
                handleMediaDelete(context, message);
                break;
            default:
                break;
        }
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
            long afterTs = db.cacheDao().getMaxChatTs();
            final String pairCode = new PrefsManager(context).getPairCode();
            if (pairCode == null) return;
            AuthManager.i(context).get(context,
                    "/api/chats/" + pairCode + "?afterTs=" + afterTs,
                    new AuthManager.Callback() {
                        @Override
                        public void onSuccess(JsonObject data) {
                            try {
                                JsonArray arr = data.getAsJsonArray("chats");
                                if (arr == null) return;
                                java.util.List<ChatEntity> list = new java.util.ArrayList<>();
                                for (int i = 0; i < arr.size(); i++) {
                                    JsonObject o = arr.get(i).getAsJsonObject();
                                    long ts = o.has("ts") ? o.get("ts").getAsLong() : System.currentTimeMillis();
                                    String text = o.has("text") ? o.get("text").getAsString() : "";
                                    boolean isSystem = o.has("isSystem") && o.get("isSystem").getAsBoolean();
                                    // 服务器 from_id/fromUser 为账号 ID，本地无用户 ID 映射：
                                    // 历史消息按 peer 消息渲染（isSelf=false），联调阶段待 Wave-2 完善映射
                                    list.add(new ChatEntity(isSystem ? "system" : "chat",
                                            text, null, false, ts));
                                }
                                AppDatabase.dbExecutor.execute(() -> {
                                    for (ChatEntity e : list) {
                                        db.chatDao().insert(e);
                                    }
                                    Log.i(TAG, "聊天历史增量同步: " + list.size() + " 条 (afterTs=" + afterTs + ")");
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "同步聊天失败", e);
                            }
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
        e.mime = p.get("mime") instanceof String ? (String) p.get("mime") : null;
        e.size = p.get("size") instanceof Number ? ((Number) p.get("size")).longValue() : 0;
        e.duration = p.get("duration") instanceof Number ? ((Number) p.get("duration")).longValue() : 0;
        e.ts = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        AppDatabase db = AppDatabase.getInstance(context);
        AppDatabase.dbExecutor.execute(() -> db.cacheDao().upsertMedia(e));
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