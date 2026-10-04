package com.eyemonitor;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

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
 * ⑨ 仪器测试：地图页打开（注入假登录/配对态；高德 view 容器本身可见，不依赖 Key）。
 * 运行：仅本地模拟器。
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MapOpenUiTest extends UiTestBase {

    /** 预授权（消除 PermissionHelper 自动弹窗） */
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
        ActivityScenario.launch(MainActivity.class);
    }

    @Test
    public void openMap_fromChatHeader() {
        onView(withId(R.id.view_chat_panel)).check(matches(isDisplayed()));
        onView(withId(R.id.btn_chat_map)).perform(click());
        // 地图容器可见（MapActivity 前台）
        onView(withId(R.id.map_view)).check(matches(isDisplayed()));
    }
}