package com.eyemonitor;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.Manifest;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.GrantPermissionRule;

import com.eyemonitor.ui.MainActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * ⑨ 仪器测试：聊天主链路 UI 走查（注入假登录/配对态，本地渲染，不依赖服务器）。
 * 覆盖：发送文本 → 本地气泡出现并清空输入框；更多面板开关；头栏状态行可见。
 * 运行：仅本地模拟器（MuMu emulator-5554 / AS AVD emulator-5556），CI 不跑。
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ChatFlowUiTest extends UiTestBase {

    /** 预授权全部运行时权限（消除 PermissionHelper 自动弹窗顶掉 MainActivity） */
    @Rule
    public GrantPermissionRule permissionRule = GrantPermissionRule.grant(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO);

    @Before
    public void seedAndLaunch() {
        seedLoggedInPaired();
        // 实证：seed 必须已生效（否则 MainActivity 会跳登录页）
        android.content.Context ctx =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.eyemonitor.config.PrefsManager pm = new com.eyemonitor.config.PrefsManager(ctx);
        org.junit.Assert.assertTrue("seed 未生效：isLoggedIn=" + pm.isLoggedIn()
                + ", token=" + pm.getAccessToken(), pm.isLoggedIn());
        ActivityScenario.launch(MainActivity.class);
    }

    @Test
    public void sendText_showsLocalBubble_andClearsInput() {
        onView(withId(R.id.view_chat_panel)).check(matches(isDisplayed()));

        onView(withId(R.id.et_chat_input)).perform(replaceText("instrumented-bubble-001"));
        onView(withId(R.id.btn_send)).perform(click());

        // 发送后输入框清空
        onView(withId(R.id.et_chat_input)).check(matches(withText("")));
        // 本地气泡出现（含文本，未送达态也渲染）
        onView(withText("instrumented-bubble-001")).check(matches(isDisplayed()));
    }

    @Test
    public void morePanel_toggles() {
        onView(withId(R.id.view_chat_panel)).check(matches(isDisplayed()));

        onView(withId(R.id.btn_chat_more)).perform(click());
        onView(withId(R.id.more_panel)).check(matches(isDisplayed()));

        onView(withId(R.id.btn_chat_more)).perform(click());
    }

    @Test
    public void headerStatusBar_visible() {
        onView(withId(R.id.view_chat_panel)).check(matches(isDisplayed()));
        // 注入态：在线 + 充电中 → 在线文字可见
        onView(withText("在线")).check(matches(isDisplayed()));
    }
}