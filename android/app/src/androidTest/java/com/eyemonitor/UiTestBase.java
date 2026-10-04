package com.eyemonitor;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;

/** ⑨ 仪器测试基类：注入"已登录+已配对"假态（专测 UI 渲染，不依赖真实服务器/账号）。 */
public abstract class UiTestBase {

    /** 与 PrefsManager.PREF_NAME 一致（测试无法访问 private 常量，字面量同步） */
    private static final String PREF_NAME = "eye_monitor_prefs";

    @Before
    public void seedLoggedInPaired() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();
        e.putString("access_token", "ui-test-fake-token");
        e.putString("pair_code", "245163");
        e.putBoolean("pair_awaiting_peer", false);
        e.putString("nickname", "测试我");
        e.putString("peer_nickname", "测试对方");
        e.putBoolean("peer_online", true);
        e.putInt("peer_battery", 88);
        e.putBoolean("peer_charging", true);
        e.putString("peer_network", "wifi");
        e.putBoolean("peer_bluetooth", true);
        e.putString("server_url", "ws://127.0.0.1:9/ws/eye");
        // 关键：指向不可达端口 → WS 连接必然失败（无 403 response）→ 走 onError+退避重连分支，
        // 不触发 onAuthExpired/KICKED；fetchAffection 等 HTTP 也静默失败。
        // 若指向真实服务器：假 token 握手 403 → onAuthExpired → MonitorService 侦线 → 测试被踢回登录页。
        // 电池优化引导：置 prompted=true 跳过系统设置对话框（否则弹窗顶掉 MainActivity，Espresso 失 RESUMED）
        e.putBoolean("battery_whitelist_prompted", true);
        // 监控设置引导（使用情况/无障碍缺失时的弹窗）：次数置满跳过（否则 300ms 后弹窗，测试收尾时 BadToken）
        e.putInt("monitor_prompt_count", 99);
        // commit() 同步落盘：确保 ActivityScenario 启动前数据已可读（apply 异步有竞争窗口）
        boolean ok = e.commit();
        org.junit.Assert.assertTrue("seed prefs commit 失败", ok);
    }
}