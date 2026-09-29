package com.eyemonitor.ui;

import android.os.Bundle;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;

/**
 * 页面基类（UI 护栏 v1，2025-09）
 *
 * 新页面一律继承本类（不要直接继承 AppCompatActivity）：
 * 1. 系统栏配色兜底：状态栏 = 主题主色（珊瑚粉），导航栏 = 表面色
 * 2. 预留：后续换肤/深色、Insets 安全区适配在此统一做
 *
 * 用法：
 * public class XxxActivity extends BaseActivity { ... }
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupSystemBars();
    }

    /** 系统栏配色（与 Theme.EyeMonitor 一致；集中在此以便未来统一调整） */
    private void setupSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        window.setStatusBarColor(getColor(R.color.primary));
        window.getDecorView().setSystemUiVisibility(0); // 浅色状态栏图标关闭（白字）
        window.setNavigationBarColor(getColor(R.color.surface));
    }
}