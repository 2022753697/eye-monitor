package com.eyemonitor.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.websocket.WSClient;

/**
 * 配对界面。
 * <p>
 * 两种模式：
 * 1. 创建配对 -> 获取 6 位码，分享给另一台设备
 * 2. 加入配对 -> 输入 6 位码，加入已有配对
 */
public class PairActivity extends AppCompatActivity {

    private static final String TAG = "PairActivity";

    /** 首页传入的配对码（自动加入模式） */
    public static final String EXTRA_PAIR_CODE = "pair_code";

    private PrefsManager prefs;
    private TextView tvPairCode;
    private EditText etPairCode;
    private Button btnCreate;
    private Button btnJoin;
    private Button btnBack;
    private WSClient wsClient;
    private boolean pairingComplete;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair);

        prefs = new PrefsManager(this);

        tvPairCode = findViewById(R.id.tv_pair_code);
        etPairCode = findViewById(R.id.et_pair_code);
        btnCreate = findViewById(R.id.btn_create);
        btnJoin = findViewById(R.id.btn_join);
        btnBack = findViewById(R.id.btn_back);

        btnCreate.setOnClickListener(v -> createPairing());
        btnJoin.setOnClickListener(v -> joinPairing());
        btnBack.setOnClickListener(v -> {
            finish();
        });

        // 首页输入配对码后跳转：自动加入
        String presetCode = getIntent().getStringExtra(EXTRA_PAIR_CODE);
        if (presetCode != null && !presetCode.isEmpty()) {
            etPairCode.setText(presetCode);
            joinPairing();
        }
    }

    @Override
    protected void onDestroy() {
        // 配对完成后 WSClient 已交给 MonitorService 管理，不要断开连接
        // 如果配对未完成（如配对失败），才需要断开
        if (!pairingComplete) {
            disconnectWs();
        }
        // else: MonitorService 已持有 wsClient 的引用，由它负责生命周期
        super.onDestroy();
    }

    private void createPairing() {
        tvPairCode.setText(R.string.pair_connecting);
        connectWs(null); // 不带 pairCode，服务器会生成新码
    }

    private void joinPairing() {
        String code = etPairCode.getText().toString().trim();
        if (code.isEmpty()) {
            Toast.makeText(this, R.string.pair_input_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.length() != 6) {
            Toast.makeText(this, R.string.pair_input_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        tvPairCode.setText(R.string.pair_connecting);
        connectWs(code);
    }

    private void connectWs(String pairCode) {
        disconnectWs();

        String serverUrl = prefs.getServerUrl();
        wsClient = new WSClient(serverUrl, new WSClient.WsCallback() {
            @Override
            public void onConnected() {
                Log.d(TAG, "WS 已连接（配对）");
                // 连接成功后发送配对请求
                WsMessage req = WsMessage.createPairRequest(prefs.getDeviceId(), pairCode);
                wsClient.send(req);
            }

            @Override
            public void onMessage(WsMessage message) {
                runOnUiThread(() -> handlePairResponse(message));
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    if (!pairingComplete) {
                        tvPairCode.setText(R.string.pair_disconnected);
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    tvPairCode.setText(getString(R.string.pair_connect_failed, msg));
                });
            }
        });

        // 登录态下握手必须携带 token（服务端 HandlerInterceptor 校验）
        wsClient.setAuthToken(prefs.getAccessToken());
        wsClient.connect();
    }

    private void handlePairResponse(WsMessage message) {
        switch (message.getType()) {
            case "pair_confirm":
                pairingComplete = true;
                String code = message.getPayload() != null
                        ? (String) message.getPayload().get("pairCode") : null;
                Object peerOnlineObj = message.getPayload() != null
                        ? message.getPayload().get("peerOnline") : null;
                boolean peerOnline = peerOnlineObj instanceof Boolean ? (Boolean) peerOnlineObj : false;
                if (code != null) {
                    prefs.setPairCode(code);
                    if (peerOnline) {
                        tvPairCode.setText(getString(R.string.pair_success_with_code, code));
                        Toast.makeText(this, R.string.pair_success, Toast.LENGTH_SHORT).show();
                    } else {
                        tvPairCode.setText(getString(R.string.pair_code_share, code));
                        Toast.makeText(this, R.string.pair_created_share, Toast.LENGTH_LONG).show();
                    }
                    // 启动监控服务，由它管理 WebSocket 和 App 切换监控
                    startMonitorService();
                }
                break;

            case "error":
                String errMsg = message.getPayload() != null
                        ? (String) message.getPayload().get("message") : getString(R.string.pair_failed, "");
                tvPairCode.setText(getString(R.string.pair_failed, errMsg));
                Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show();
                disconnectWs();
                break;

            default:
                tvPairCode.setText(getString(R.string.pair_unknown_response, message.getType()));
        }
    }

    private void disconnectWs() {
        mainHandler.removeCallbacksAndMessages(null); // 取消延迟断开
        if (wsClient != null) {
            wsClient.disconnect();
            wsClient = null;
        }
    }

    private void startMonitorService() {
        // 将 WSClient 交给 MonitorService 管理
        MonitorService.setSharedWSClient(wsClient);
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}