package com.eyemonitor.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用名解析器：把包名解析为用户可读的应用名（如 com.android.chrome → 谷歌浏览器）。
 * <p>
 * 解析顺序：
 * 1. 系统 ApplicationInfo 的 label（用户安装时看到的名称）
 * 2. 内置常见应用中文名映射表（label 缺失/为包名时兜底）
 * 3. 原样返回包名
 */
public class AppNameResolver {

    private static final String TAG = "AppNameResolver";

    /** 常见应用包名 → 中文常用名（模拟器/无 label 场景兜底，也统一中文显示） */
    private static final Map<String, String> KNOWN_APP_NAMES = new HashMap<>();

    static {
        // 浏览器 / 搜索
        KNOWN_APP_NAMES.put("com.android.chrome", "谷歌浏览器");
        KNOWN_APP_NAMES.put("com.tencent.mtt", "QQ浏览器");
        KNOWN_APP_NAMES.put("com.UCMobile", "UC浏览器");
        KNOWN_APP_NAMES.put("com.microsoft.emmx", "Edge浏览器");
        KNOWN_APP_NAMES.put("com.baidu.searchbox", "百度");
        // 社交 / 通讯
        KNOWN_APP_NAMES.put("com.tencent.mm", "微信");
        KNOWN_APP_NAMES.put("com.tencent.mobileqq", "QQ");
        KNOWN_APP_NAMES.put("com.tencent.tim", "TIM");
        KNOWN_APP_NAMES.put("com.tencent.wework", "企业微信");
        KNOWN_APP_NAMES.put("com.sina.weibo", "微博");
        KNOWN_APP_NAMES.put("com.xingin.xhs", "小红书");
        KNOWN_APP_NAMES.put("com.zhihu.android", "知乎");
        KNOWN_APP_NAMES.put("com.whatsapp", "WhatsApp");
        KNOWN_APP_NAMES.put("com.telegram.messenger", "Telegram");
        // 短视频 / 视频
        KNOWN_APP_NAMES.put("com.ss.android.ugc.aweme", "抖音");
        KNOWN_APP_NAMES.put("com.ss.android.ugc.aweme.lite", "抖音极速版");
        KNOWN_APP_NAMES.put("com.tencent.qqlive", "腾讯视频");
        KNOWN_APP_NAMES.put("com.youku.phone", "优酷");
        KNOWN_APP_NAMES.put("com.google.android.youtube", "YouTube");
        KNOWN_APP_NAMES.put("com.bilibili.app.blue", "哔哩哔哩");
        // 购物 / 支付
        KNOWN_APP_NAMES.put("com.tencent.mm", "微信");
        KNOWN_APP_NAMES.put("com.eg.android.AlipayGphone", "支付宝");
        KNOWN_APP_NAMES.put("com.taobao.taobao", "淘宝");
        KNOWN_APP_NAMES.put("com.jingdong.app.mall", "京东");
        KNOWN_APP_NAMES.put("com.xunmeng.pinduoduo", "拼多多");
        // 音乐
        KNOWN_APP_NAMES.put("com.netease.cloudmusic", "网易云音乐");
        KNOWN_APP_NAMES.put("com.tencent.qqmusic", "QQ音乐");
        KNOWN_APP_NAMES.put("com.kugou.android", "酷狗音乐");
        // 地图 / 出行
        KNOWN_APP_NAMES.put("com.autonavi.minimap", "高德地图");
        KNOWN_APP_NAMES.put("com.baidu.BaiduMap", "百度地图");
        KNOWN_APP_NAMES.put("com.google.android.apps.maps", "谷歌地图");
        KNOWN_APP_NAMES.put("com.sdu.didi.psnger", "滴滴出行");
        // 系统 / 常用工具（模拟器场景）
        KNOWN_APP_NAMES.put("com.android.settings", "设置");
        KNOWN_APP_NAMES.put("com.android.deskclock", "时钟");
        KNOWN_APP_NAMES.put("com.android.phone", "电话");
        KNOWN_APP_NAMES.put("com.android.messaging", "信息");
        KNOWN_APP_NAMES.put("com.android.camera", "相机");
        KNOWN_APP_NAMES.put("com.android.gallery3d", "相册");
        KNOWN_APP_NAMES.put("com.google.android.apps.nexuslauncher", "桌面");
        KNOWN_APP_NAMES.put("app.lawnchair", "桌面");
        KNOWN_APP_NAMES.put("com.google.android.gm", "Gmail");
        KNOWN_APP_NAMES.put("com.google.android.calendar", "日历");
        KNOWN_APP_NAMES.put("com.google.android.contacts", "通讯录");
    }

    private AppNameResolver() {}

    /**
     * 解析应用名：系统 label 优先，映射表兜底，最后回退包名。
     */
    public static String getAppName(Context context, String packageName) {
        if (packageName == null) return "未知应用";

        // 1. 系统 label（用户看到的应用名）
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            CharSequence label = ai.loadLabel(pm);
            if (label != null && label.length() > 0) {
                String s = label.toString().trim();
                // label 有效且不是包名本身时使用；否则继续走映射表
                if (!s.isEmpty() && !s.equals(packageName)) {
                    return s;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "label 解析失败: " + packageName);
        }

        // 2. 内置中文名映射
        String known = KNOWN_APP_NAMES.get(packageName);
        if (known != null) return known;

        // 3. 回退包名
        return packageName;
    }
}
