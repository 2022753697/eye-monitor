package com.eyemonitor.websocket;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.eyemonitor.model.WsMessage;
import com.eyemonitor.util.HostnamePolicy;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp WebSocket 客户端。
 * <p>
 * 功能：
 * - 自动连接服务器 ws://host:port/ws/eye
 * - 指数退避重连（1s, 2s, 4s, 8s... 最大 30s）
 * - 心跳保活（每 30 秒 ping）
 * - 连接状态回调
 */
public class WSClient {

    private static final String TAG = "WSClient";

    // 重连参数
    private static final long RECONNECT_BASE_DELAY_MS = 1000;
    private static final long RECONNECT_MAX_DELAY_MS = 30_000;
    /** OkHttp 原生 ping：固定长值兜底（防 NAT 全空转；真实保活走应用层心跳，见 setScreenOn） */
    private static final long OKHTTP_PING_INTERVAL_MS = 300_000;
    /** 应用层心跳：亮屏 30s ／ 息屏 120s（省电 P2） */
    private static final long HEARTBEAT_SCREEN_ON_MS = 30_000L;
    private static final long HEARTBEAT_SCREEN_OFF_MS = 120_000L;
    /** 重连成功后短心跳稳连次数（自适应 NAT：先 30s 稳连，再回落档位） */
    private static final int HEARTBEAT_STABILIZE_TICKS = 3;

    // 回调接口
    public interface WsCallback {
        /** 连接成功建立 */
        void onConnected();
        /** 收到消息 */
        void onMessage(WsMessage message);
        /** 连接断开 */
        void onDisconnected();
        /** 发生错误 */
        void onError(String message);
        /** 被顶替下线（服务端 error{KICKED}）——默认空实现，不强制实现方改 */
        default void onKicked(WsMessage message) {}
        /** token 失效未通过鉴权（握手 401/403）——由监听方触发无感刷新后重连 */
        default void onAuthExpired() {}
    }

    private final OkHttpClient client;
    private final String serverUrl;
    private WsCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 握手令牌（可热更新：无感刷新后 setAuthToken + connect 重连） */
    private volatile String authToken;

    private WebSocket webSocket;
    private boolean connected;
    private boolean shouldReconnect = true;
    private long reconnectDelay = RECONNECT_BASE_DELAY_MS;
    private boolean destroyed;

    private final Runnable pingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            sendPing();
            scheduleNextPing();
        }
    };
    private final Runnable reconnectRunnable = this::doConnect;

    /** 应用层心跳：当前档位（亮屏 30s / 息屏 120s） */
    private volatile boolean screenOn = true;
    private int stabilizeTicks;

    public WSClient(String serverUrl, WsCallback callback) {
        this.serverUrl = serverUrl;
        this.callback = callback;
        // OkHttp 客户端：原生 ping 拉长到 300s 兜底（省电 P2，真实保活走应用层心跳）
        this.client = new OkHttpClient.Builder()
                .pingInterval(OKHTTP_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket 不设超时
                // IP 直连时放开主机名校验（证书为域名证书）；域名连接保持严格
                .hostnameVerifier(HostnamePolicy.verifierFor(serverUrl))
                .build();
    }

    /** 建立连接 */
    public void connect() {
        if (destroyed) return;
        shouldReconnect = true;
        doConnect();
    }

    /** 断开连接 */
    public void disconnect() {
        shouldReconnect = false;
        destroyed = true;
        cancelReconnect();
        cancelPing();
        closeWebSocket();
    }

    /** 发送消息 */
    public boolean send(WsMessage message) {
        if (webSocket != null && connected) {
            String json = message.toJson();
            Log.d(TAG, "发送: " + json);
            return webSocket.send(json);
        }
        Log.w(TAG, "未连接，无法发送消息");
        return false;
    }

    /** 是否已连接 */
    public boolean isConnected() {
        return connected;
    }

    /** 更新回调（用于配对完成后转移给 MonitorService） */
    public void setCallback(WsCallback callback) {
        this.callback = callback;
    }

    /** 获取当前回调 */
    public WsCallback getCallback() {
        return callback;
    }

    /** 设置握手令牌（无感刷新后调用 connect() 携带新 token 重连） */
    public void setAuthToken(String token) {
        this.authToken = token;
    }

    // --- 内部方法 ---

    private void doConnect() {
        if (destroyed) return;

        cancelReconnect();
        closeWebSocket();

        // F-03 阶段 2（安全加固 2026-10）：token 改走握手请求头 X-Auth-Token，不再拼 query（防日志泄露）
        String url = serverUrl;
        Request.Builder rb = new Request.Builder().url(url);
        if (authToken != null && !authToken.isEmpty()) {
            rb.header("X-Auth-Token", authToken);
            Log.d(TAG, "连接携带 token（Header）: " + url.replace(authToken, "***"));
        }
        Log.d(TAG, "正在连接: " + url);
        Request request = rb.build();
        webSocket = client.newWebSocket(request, new WsListener());
    }

    private void closeWebSocket() {
        if (webSocket != null) {
            try {
                webSocket.close(1000, "客户端主动关闭");
            } catch (Exception ignored) {}
            webSocket = null;
        }
    }

    /** 触发重连（指数退避） */
    private void scheduleReconnect() {
        if (!shouldReconnect || destroyed) return;

        Log.d(TAG, "计划重连，延迟: " + reconnectDelay + "ms");
        mainHandler.postDelayed(reconnectRunnable, reconnectDelay);

        // 指数退避，最大 30s
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_DELAY_MS);
    }

    private void cancelReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable);
    }

    /** 屏态变化：亮屏 30s 心跳 ／ 息屏 120s（消息推送不受影响，仍实时到达） */
    public void setScreenOn(boolean on) {
        if (screenOn == on) return;
        screenOn = on;
        Log.i(TAG, "屏态变化: " + (on ? "亮屏" : "息屏") + ", 心跳间隔=" + currentHeartbeatMs() + "ms");
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(pingRunnable);
            scheduleNextPing();
        });
    }

    private long currentHeartbeatMs() {
        return screenOn ? HEARTBEAT_SCREEN_ON_MS : HEARTBEAT_SCREEN_OFF_MS;
    }

    /** 连接建立后启动心跳（重连成功先短心跳稳连，自适应 NAT） */
    private void startHeartbeat() {
        mainHandler.removeCallbacks(pingRunnable);
        stabilizeTicks = HEARTBEAT_STABILIZE_TICKS;
        scheduleNextPing();
        Log.d(TAG, "心跳已启动, 档=" + currentHeartbeatMs() + "ms");
    }

    private void scheduleNextPing() {
        long interval = currentHeartbeatMs();
        if (stabilizeTicks > 0) {
            stabilizeTicks--;
            interval = HEARTBEAT_SCREEN_ON_MS; // 稳连期 30s 短心跳
        }
        mainHandler.postDelayed(pingRunnable, interval);
    }

    /** 发送应用层心跳 ping */
    private void sendPing() {
        if (connected && webSocket != null) {
            Log.d(TAG, "HEARTBEAT帧: ping"); // ASCII 计数标记（P4 对比用）
            webSocket.send("{\"type\":\"ping\",\"deviceId\":\"\",\"payload\":{},\"timestamp\":"
                    + System.currentTimeMillis() + "}");
        }
    }

    private void cancelPing() {
        mainHandler.removeCallbacks(pingRunnable);
    }

    private void notifyConnected() {
        connected = true;
        reconnectDelay = RECONNECT_BASE_DELAY_MS; // 重置重连延迟
        startHeartbeat();
        if (callback != null) callback.onConnected();
    }

    private void notifyDisconnected() {
        connected = false;
        cancelPing();
        if (callback != null) callback.onDisconnected();
    }

    // --- WebSocketListener ---

    private class WsListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.i(TAG, "连接已建立");
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyConnected();
                }
            });
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            Log.d(TAG, "收到消息: " + text);

            WsMessage msg = WsMessage.fromJson(text);
            if (msg == null) {
                Log.w(TAG, "消息解析失败: " + text);
                return;
            }

            // pong 直接忽略（OkHttp 原生心跳已处理）
            if ("pong".equals(msg.getType())) return;

            Log.i(TAG, "WSClient收到: type=" + msg.getType() + ", deviceId=" + msg.getDeviceId()
                    + ", callback=" + (callback != null ? "not null" : "null")
                    + ", callbackClass=" + (callback != null ? callback.getClass().getName() : "N/A"));

            // 直接调用回调（OkHttp在后台线程调用，但broadcast不依赖主线程）
            if (callback != null) {
                try {
                    // 被顶替下线：先通知 onKicked，再由调用方决定收发与清理
                    if ("error".equals(msg.getType()) && msg.getPayload() != null
                            && "KICKED".equals(msg.getPayload().get("code"))) {
                        Log.w(TAG, "收到 KICKED，触发 onKicked 回调");
                        callback.onKicked(msg);
                    }
                    Log.i(TAG, "调用回调 onMessage, thread=" + Thread.currentThread().getName());
                    callback.onMessage(msg);
                    Log.i(TAG, "回调完成");
                } catch (Exception e) {
                    Log.e(TAG, "回调执行异常: " + e.getMessage(), e);
                }
            } else {
                Log.w(TAG, "callback 为 null！消息被丢弃: " + msg.getType());
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            Log.d(TAG, "连接关闭中: code=" + code + ", reason=" + reason);
            ws.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.i(TAG, "连接已关闭: code=" + code + ", reason=" + reason);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyDisconnected();
                    scheduleReconnect();
                }
            });
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "连接失败: " + (t != null ? t.getMessage() : "未知错误")
                    + ", httpCode=" + (response != null ? response.code() : -1));
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyDisconnected();
                    // 握手鉴权失败（token 过期/吊销）：交回上层无感刷新，不再盲目重连带旧 token
                    if (response != null && (response.code() == 401 || response.code() == 403)) {
                        Log.w(TAG, "握手鉴权失败，触发 onAuthExpired");
                        if (callback != null) callback.onAuthExpired();
                        return;
                    }
                    if (callback != null) {
                        callback.onError(t != null ? t.getMessage() : "连接失败");
                    }
                    scheduleReconnect();
                }
            });
        }
    }
}
