package com.eyemonitor.util;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.eyemonitor.service.AppAccessibilityService;

/**
 * 无障碍服务诊断工具
 * 用于检测无障碍服务状态和提供修复引导
 */
public class AccessibilityDiagnostic {

    private static final String TAG = "AccessibilityDiagnostic";

    /**
     * 检查无障碍服务是否已启用
     */
    public static boolean isAccessibilityEnabled(Context context) {
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabledServices == null || enabledServices.isEmpty()) {
                Log.w(TAG, "无障碍服务未启用");
                return false;
            }

            String myService = context.getPackageName() + "/" + AppAccessibilityService.class.getName();
            boolean isEnabled = enabledServices.contains(myService);
            Log.i(TAG, "无障碍服务状态: " + (isEnabled ? "已启用" : "未启用"));
            Log.d(TAG, "当前已启用服务: " + enabledServices);

            return isEnabled;
        } catch (Exception e) {
            Log.e(TAG, "检查无障碍服务状态异常", e);
            return false;
        }
    }

    /**
     * 检查无障碍服务实例是否可用
     */
    public static boolean isServiceInstanceAvailable() {
        boolean available = AppAccessibilityService.getInstance() != null;
        Log.i(TAG, "无障碍服务实例: " + (available ? "可用" : "不可用"));
        return available;
    }

    /**
     * 获取无障碍服务启用状态的详细诊断信息
     */
    public static String getDiagnosticInfo(Context context) {
        StringBuilder sb = new StringBuilder();

        // 检查设置中的状态
        boolean enabledInSettings = isAccessibilityEnabled(context);
        sb.append("设置中状态: ").append(enabledInSettings ? "已启用" : "未启用").append("\n");

        // 检查服务实例
        boolean instanceAvailable = isServiceInstanceAvailable();
        sb.append("服务实例: ").append(instanceAvailable ? "可用" : "不可用").append("\n");

        // 检查监听器
        boolean listenerSet = AppAccessibilityService.getInstance() != null
                && AppAccessibilityService.getInstance().isListenerSet();
        sb.append("监听器: ").append(listenerSet ? "已设置" : "未设置").append("\n");

        // 最后检测到的包名
        String lastPkg = AppAccessibilityService.getInstance() != null
                ? AppAccessibilityService.getInstance().getLastPackageName()
                : "未知";
        sb.append("最后检测包名: ").append(lastPkg).append("\n");

        return sb.toString();
    }

    /**
     * 打开无障碍服务设置页面
     */
    public static void openAccessibilitySettings(Context context) {
        Log.i(TAG, "打开无障碍设置页面");
        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
        context.startActivity(intent);
    }

    /**
     * 提供修复建议
     */
    public static String getFixSuggestion(Context context) {
        if (!isAccessibilityEnabled(context)) {
            return "无障碍服务未启用！\n\n" +
                   "请按以下步骤开启：\n" +
                   "1. 打开手机「设置」\n" +
                   "2. 找到「辅助功能」或「无障碍」\n" +
                   "3. 找到「眼互」并开启开关\n" +
                   "4. 返回应用重新测试";
        }

        if (AppAccessibilityService.getInstance() == null) {
            return "无障碍服务已启用但实例不可用。\n\n" +
                   "请尝试：\n" +
                   "1. 完全关闭应用\n" +
                   "2. 清除应用数据\n" +
                   "3. 重新安装应用\n" +
                   "4. 重新开启无障碍服务";
        }

        return "无障碍服务状态正常。\n\n" +
               "请检查：\n" +
               "1. 通知权限是否已授权\n" +
               "2. 服务器连接是否正常\n" +
               "3. 两台设备是否已配对";
    }
}
