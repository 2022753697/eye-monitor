package com.eyemonitor.config;

import com.eyemonitor.entity.AppNameEntity;
import com.eyemonitor.repository.AppNameRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用名映射种子数据（首次启动注入 eye_app_names）。
 * <p>
 * 仅当表为空时写入；此后以数据库为准，可直接在库里增删改，
 * 客户端下次 GET /api/app-names 同步即生效（无需重新发版）。
 */
@Component
public class AppNameSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AppNameSeeder.class);

    private final AppNameRepo appNameRepo;

    public AppNameSeeder(AppNameRepo appNameRepo) {
        this.appNameRepo = appNameRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (appNameRepo.count() > 0) {
            return;
        }
        Map<String, String> seed = new LinkedHashMap<>();
        // 浏览器 / 搜索
        seed.put("com.android.chrome", "谷歌浏览器");
        seed.put("com.tencent.mtt", "QQ浏览器");
        seed.put("com.UCMobile", "UC浏览器");
        seed.put("com.microsoft.emmx", "Edge浏览器");
        seed.put("com.baidu.searchbox", "百度");
        // 社交 / 通讯
        seed.put("com.tencent.mm", "微信");
        seed.put("com.tencent.mobileqq", "QQ");
        seed.put("com.tencent.tim", "TIM");
        seed.put("com.tencent.wework", "企业微信");
        seed.put("com.sina.weibo", "微博");
        seed.put("com.xingin.xhs", "小红书");
        seed.put("com.zhihu.android", "知乎");
        seed.put("com.whatsapp", "WhatsApp");
        seed.put("com.telegram.messenger", "Telegram");
        // 短视频 / 视频
        seed.put("com.ss.android.ugc.aweme", "抖音");
        seed.put("com.ss.android.ugc.aweme.lite", "抖音极速版");
        seed.put("com.tencent.qqlive", "腾讯视频");
        seed.put("com.youku.phone", "优酷");
        seed.put("com.google.android.youtube", "YouTube");
        seed.put("com.bilibili.app.blue", "哔哩哔哩");
        // 购物 / 支付
        seed.put("com.eg.android.AlipayGphone", "支付宝");
        seed.put("com.taobao.taobao", "淘宝");
        seed.put("com.jingdong.app.mall", "京东");
        seed.put("com.xunmeng.pinduoduo", "拼多多");
        // 音乐
        seed.put("com.netease.cloudmusic", "网易云音乐");
        seed.put("com.tencent.qqmusic", "QQ音乐");
        seed.put("com.kugou.android", "酷狗音乐");
        // 地图 / 出行
        seed.put("com.autonavi.minimap", "高德地图");
        seed.put("com.baidu.BaiduMap", "百度地图");
        seed.put("com.google.android.apps.maps", "谷歌地图");
        seed.put("com.sdu.didi.psnger", "滴滴出行");
        // 系统 / 常用工具（模拟器场景）
        seed.put("com.android.settings", "设置");
        seed.put("com.android.deskclock", "时钟");
        seed.put("com.android.phone", "电话");
        seed.put("com.android.messaging", "信息");
        seed.put("com.android.camera", "相机");
        seed.put("com.android.gallery3d", "相册");
        seed.put("com.google.android.apps.nexuslauncher", "桌面");
        seed.put("app.lawnchair", "桌面");
        seed.put("com.google.android.gm", "Gmail");
        seed.put("com.google.android.calendar", "日历");
        seed.put("com.google.android.contacts", "通讯录");

        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> e : seed.entrySet()) {
            appNameRepo.save(new AppNameEntity(e.getKey(), e.getValue(), now));
        }
        log.info("应用名映射种子注入完成: {} 条", seed.size());
    }
}
