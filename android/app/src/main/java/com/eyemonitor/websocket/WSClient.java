package com.eyemonitor.websocket;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.eyemonitor.model.WsMessage;

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
    private static final long PING_INTERVAL_MS = 30_000;

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
    }

    private final OkHttpClient client;
    private final String serverUrl;
    private WsCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebSocket webSocket;
    private boolean connected;
    private boolean shouldReconnect = true;
    private long reconnectDelay = RECONNECT_BASE_DELAY_MS;
    private boolean destroyed;

    private final Runnable pingRunnable = this::sendPing;
    private final Runnable reconnectRunnable = this::doConnect;

    public WSClient(String serverUrl, WsCallback callback) {
        this.serverUrl = serverUrl;
        this.callback = callback;
        // OkHttp 客户端：原生 ping 间隔 30s
        this.client = new OkHttpClient.Builder()
                .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket 不设超时
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

    // --- 内部方法 ---

    private void doConnect() {
        if (destroyed) return;

        cancelReconnect();
        closeWebSocket();

        Log.d(TAG, "正在连接: " + serverUrl);
        Request request = new Request.Builder()
                .url(serverUrl)
                .build();
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

    private void sendPing() {
        if (connected && webSocket != null) {
            Log.d(TAG, "发送 ping");
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
        if (callback != null) callback.onConnected();
    }

    private void notifyDisconnected() {
        connected = false;
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
            Log.e(TAG, "连接失败: " + (t != null ? t.getMessage() : "未知错误"));
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyDisconnected();
                    if (callback != null) {
                        callback.onError(t != null ? t.getMessage() : "连接失败");
                    }
                    scheduleReconnect();
                }
            });
        }
    }
}
