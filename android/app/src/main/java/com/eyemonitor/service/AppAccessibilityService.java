package com.eyemonitor.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.eyemonitor.util.AppNameResolver;

/**
 * 无障碍服务 - 检测前台 App 切换
 * <p>
 * 替代 PACKAGE_USAGE_STATS 权限的方案
 * 需要在系统设置的"无障碍"中手动启用
 */
public class AppAccessibilityService extends AccessibilityService {

    private static final String TAG = "AppAccessibilityService";

    public interface OnAppSwitchListener {
        void onAppSwitched(String packageName, String appName);
    }

    private static AppAccessibilityService instance;
    private OnAppSwitchListener listener;
    private volatile String lastPackageName;
    /** 上次确认并已上报的包名：同包不重复上报（Chrome 等 App 内部窗口变化不再误报） */
    private volatile String lastConfirmedPkg;
    private volatile long lastEventTime = 0;
    private static final long MIN_EVENT_INTERVAL_MS = 2000; // 2秒防抖
    private volatile String pendingPackageName; // 待确认的包名
    private volatile long pendingTime; // 待确认时间

    // 冷却时间机制：防止同一 App 在短时间内被重复检测
    private volatile String cooldownPackageName; // 正在冷却的 App
    private volatile long cooldownEndTime; // 冷却结束时间
    private static final long COOLDOWN_MS = 3000; // 3秒冷却时间

    /** Handler 用于延迟确认 App 切换 */
    private final android.os.Handler confirmHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 服务就绪时的回调（由 MonitorService 设置） */
    private static volatile Runnable onServiceReady;

    /** 是否已设置监听器 */
    public boolean isListenerSet() {
        return listener != null;
    }

    /** 获取最后检测到的包名 */
    public String getLastPackageName() {
        return lastPackageName;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接, instance设置成功");
        Log.i(TAG, "当前 listener: " + (listener != null ? "已设置" : "null"));
        Log.i(TAG, "当前 onServiceReady 回调: " + (onServiceReady != null ? "已注册" : "未注册"));

        AccessibilityServiceInfo config = new AccessibilityServiceInfo();
        config.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        config.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        config.notificationTimeout = 100;
        setServiceInfo(config);
        Log.i(TAG, "无障碍服务配置完成");

        // 通知 MonitorService 服务已就绪
        Runnable readyCb = onServiceReady;
        if (readyCb != null) {
            Log.i(TAG, "触发 onServiceReady 回调");
            readyCb.run();
        } else {
            Log.w(TAG, "警告：onServiceReady 回调未注册！MonitorService 可能还没启动");
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "无障碍服务 onDestroy");
        instance = null;
        confirmHandler.removeCallbacks(confirmRunnable);
        cooldownPackageName = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : null;
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";

        // 记录所有事件的详细信息用于调试
        Log.d(TAG, "收到无障碍事件: type=" + eventType + ", pkg=" + pkg + ", cls=" + cls);

        // 过滤无包名的事件
        if (pkg == null || pkg.isEmpty()) {
            return;
        }

        // 过滤自身应用
        if (pkg.equals(getPackageName())) {
            Log.d(TAG, "过滤自身应用: " + pkg);
            return;
        }

        // 过滤系统包
        if (isSystemPackage(pkg)) {
            Log.d(TAG, "过滤系统包: " + pkg);
            return;
        }

        // 匹配 app 切换相关的事件类型
        boolean isSwitchEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED;

        if (!isSwitchEvent) {
            Log.d(TAG, "非切换事件，忽略: type=" + eventType);
            return;
        }

        // 新增：过滤通知弹窗（在同一 App 内弹出的通知）
        // 如果包名相同但类名包含特定关键词，认为是弹窗而非切换
        if (pkg.equals(lastPackageName) && isPopupLike(cls)) {
            Log.d(TAG, "过滤通知弹窗: " + cls);
            return;
        }

        // 新增：过滤系统通知栏事件（如果包名是系统包但类名像通知）
        if (isSystemNotificationPkg(pkg) && isPopupLike(cls)) {
            Log.d(TAG, "过滤系统通知: " + pkg + " " + cls);
            return;
        }

        // 增强防抖：使用延迟确认机制
        long eventTime = System.currentTimeMillis();
        long timeSinceLastSwitch = eventTime - lastEventTime;

        // 如果类名包含 Dialog/Popup 且距离上次切换不到 1 秒，认为是弹窗
        if (isPopupLike(cls) && timeSinceLastSwitch < 1000) {
            Log.d(TAG, "过滤弹窗事件（距离上次 " + timeSinceLastSwitch + "ms）: " + cls);
            return;
        }

        // 新增：如果距离上次切换不到 2 秒，且是通知类事件，认为是 App 内通知而非切换
        if (timeSinceLastSwitch < 2000 && isNotificationEvent(cls) && pkg.equals(lastPackageName)) {
            Log.d(TAG, "过滤 App 内通知（距离上次 " + timeSinceLastSwitch + "ms）: " + cls);
            return;
        }

        // 新增：检查是否在冷却时间内（防止同一 App 重复检测）
        if (isInCooldown(pkg)) {
            Log.d(TAG, "过滤冷却中的 App: " + pkg + " (冷却时间剩余 " + (cooldownEndTime - System.currentTimeMillis()) + "ms)");
            return;
        }

        // 如果正在等待确认，检查是否是同一个 App
        if (pendingPackageName != null) {
            if (pkg.equals(pendingPackageName) || isSameApp(pkg, pendingPackageName)) {
                // 同一个 App，更新等待时间，不重新触发
                pendingTime = eventTime;
                Log.d(TAG, "更新待确认状态: " + pkg + " (距离上次 " + (eventTime - pendingTime) + "ms)");
                return;
            } else {
                // 不同的 App，取消之前的等待，立即确认新的
                confirmHandler.removeCallbacks(confirmRunnable);
                Log.d(TAG, "检测到新 App，取消等待: " + pendingPackageName + " -> " + pkg);
            }
        }

        // 设置待确认状态
        pendingPackageName = pkg;
        pendingTime = eventTime;
        Log.d(TAG, "设置待确认状态: " + pkg);

        // 延迟 1.5 秒后确认（如果期间没有新的切换）
        confirmHandler.postDelayed(confirmRunnable, 1500);
    }

    /**
     * 判断是否是系统通知相关的包
     */
    private boolean isSystemNotificationPkg(String packageName) {
        return packageName != null && (
            packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.android.settings") ||
            packageName.equals("android")
        );
    }

    /**
     * 判断是否是弹窗类界面（通知、对话框等）
     */
    private boolean isPopupLike(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        // 常见的弹窗/对话框类名关键词（更全面）
        String[] popupKeywords = {
            "Dialog",
            "AlertDialog",
            "Notification",
            "PopupWindow",
            "Toast",
            "Snackbar",
            "BottomSheet",
            "DialogFragment",
            "Popup",
            "Toast",
            "Banner",
            "Tip",
            "Message",
            "Alert",
            "Sheet",
            "Panel",
            "Drawer",
            "Modal",
            "Overlay",
            "Flyout",
            "Popover"
        };
        for (String keyword : popupKeywords) {
            if (className.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否是通知类事件（短信、邮件等 App 内的通知）
     * 通过分析类名特征来判断
     */
    private boolean isNotificationEvent(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        // 通知相关的类名特征
        String[] notifKeywords = {
            "Notification",
            "IncomingCall",
            "Message",
            "SMS",
            "Push",
            "Alert",
            "Toast",
            "Banner"
        };
        for (String keyword : notifKeywords) {
            if (className.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AccessibilityService 被中断");
    }

    public void setListener(OnAppSwitchListener listener) {
        this.listener = listener;
        Log.i(TAG, "setListener 完成, listener=" + (listener != null ? "已设置" : "null"));
    }

    public static AppAccessibilityService getInstance() {
        return instance;
    }

    /** 设置服务就绪回调，由 MonitorService 注册 */
    public static void setOnServiceReady(Runnable callback) {
        onServiceReady = callback;
        Log.i(TAG, "onServiceReady 回调已注册: " + (callback != null));
    }

    /**
     * 延迟确认 runnable - 1.5秒后如果没有新的切换，则触发回调
     */
    private final Runnable confirmRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingPackageName != null) {
                String confirmedPkg = pendingPackageName;
                pendingPackageName = null; // 清除待确认状态

                // 同包重复确认（App 内部窗口变化）：只更新状态，不重复上报
                if (confirmedPkg.equals(lastConfirmedPkg)) {
                    Log.d(TAG, "同包重复确认（App 内部变化），忽略: " + confirmedPkg);
                    lastEventTime = System.currentTimeMillis();
                    return;
                }
                lastConfirmedPkg = confirmedPkg;
                lastPackageName = confirmedPkg;
                lastEventTime = System.currentTimeMillis();

                // 启动冷却时间
                startCooldown(confirmedPkg);

                Log.i(TAG, "确认 App 切换: " + confirmedPkg);
                if (listener != null) {
                    String appName = getAppName(confirmedPkg);
                    Log.i(TAG, "App 切换回调: " + appName + " (" + confirmedPkg + ")");
                    listener.onAppSwitched(confirmedPkg, appName);
                } else {
                    Log.w(TAG, "listener 为 null，无法回调！");
                }
            }
        }
    };

    /**
     * 启动冷却时间
     */
    private void startCooldown(String packageName) {
        cooldownPackageName = packageName;
        cooldownEndTime = System.currentTimeMillis() + COOLDOWN_MS;
        Log.d(TAG, "启动冷却: " + packageName + " (冷却 " + COOLDOWN_MS + "ms)");
    }

    /**
     * 检查是否在冷却时间内
     */
    private boolean isInCooldown(String packageName) {
        if (cooldownPackageName == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now > cooldownEndTime) {
            // 冷却结束，清除状态
            cooldownPackageName = null;
            return false;
        }
        // 检查是否是同一个 App（考虑包名变体）
        boolean isSameApp = packageName.equals(cooldownPackageName) ||
                           (lastPackageName != null && isSameApp(packageName, lastPackageName));
        return isSameApp;
    }

    private String getAppName(String packageName) {
        return AppNameResolver.getAppName(getApplicationContext(), packageName);
    }

    private boolean isSystemPackage(String packageName) {
        // 只过滤真正的系统包，不排除用户安装的 App
        // com.android.chrome 是 Google 安装的浏览器，应该被监控
        if (packageName.equals(getPackageName())) {
            return true;
        }

        // 真正的系统包（不包含用户安装的 App）
        String[] systemPackages = {
            "com.android.settings",
            "com.android.provider",
            "com.android.contacts",
            "com.android.contacts.events",
            "com.android.exchange",
            "com.android.email",
            "com.android.calculator2",
            "com.android.camera",
            "com.android.camera2",
            "com.android.gallery",
            "com.android.mms",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.android.deskclock",
            "com.android.alarmclock",
            "com.android.calendar",
            "com.android.documentsui",
            "com.android.downloads",
            "com.android.downloads.ui",
            "com.android.certinstaller",
            "com.android.inputdevices",
            "com.android.location.fused",
            "com.android.managedprovisioning",
            "com.android.media",
            "com.android.mms.service",
            "com.android.music",
            "com.android.musicfx",
            "com.android.onc.external",
            "com.android.packageinstaller",
            "com.android.phone",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.providers.settings",
            "com.android.providers.telephony",
            "com.android.providers.userdictionary",
            "com.android.qsb",
            "com.android.samus",
            "com.android.security.credential",
            "com.android.server.telecom",
            "com.android.settings.intelligence",
            "com.android.simappdialog",
            "com.android.smspush",
            "com.android.statementservice",
            "com.android.stk",
            "com.android.sync",
            "com.android.systemui",
            "com.android.vending",
            "com.android.volte",
            "com.android.wallpaper",
            "com.android.wallpaper.livepicker",
            "com.android.wifi.dialog",
            "com.android.xml"
        };

        for (String sysPkg : systemPackages) {
            if (packageName.equals(sysPkg) || packageName.startsWith(sysPkg + ".")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断两个包名是否属于同一个 App
     * 例如：com.android.chrome 和 com.android.chrome.xxx 视为同一 App
     */
    private boolean isSameApp(String pkg1, String pkg2) {
        if (pkg1 == null || pkg2 == null) {
            return false;
        }
        // 提取基础包名（去掉最后的 .xxx 部分）
        String base1 = getBasePackageName(pkg1);
        String base2 = getBasePackageName(pkg2);
        return base1.equals(base2);
    }

    /**
     * 获取基础包名
     * 例如：com.android.chrome.TabbedActivity -> com.android.chrome
     */
    private String getBasePackageName(String packageName) {
        if (packageName == null) {
            return "";
        }
        // 查找最后一个点之后的部分，如果存在则去掉
        int lastDot = packageName.lastIndexOf('.');
        if (lastDot > 0) {
            String afterDot = packageName.substring(lastDot + 1);
            // 如果最后是 Activity 名称（大写开头），则去掉
            if (afterDot.length() > 0 && Character.isUpperCase(afterDot.charAt(0))) {
                return packageName.substring(0, lastDot);
            }
        }
        return packageName;
    }
}
