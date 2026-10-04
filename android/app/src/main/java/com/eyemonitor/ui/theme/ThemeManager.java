package com.eyemonitor.ui.theme;

import android.content.Context;

import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AffectionStateHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题管理器（数据驱动，零布局复制）。
 * <p>
 * 解锁判定：theme.unlockLevel <= 当前共享等级（AffectionStateHolder 内存镜像，
 * 随 affection_sync / GET /affection 刷新）。选择 = 各人本地偏好（PrefsManager），
 * 解锁共同、应用各自——不需要双端同步协议。
 */
public final class ThemeManager {

    private ThemeManager() {}

    /** 全部主题（按解锁等级升序） */
    public static List<ChatTheme> getThemes() {
        List<ChatTheme> list = new ArrayList<>();
        list.add(ChatTheme.buildDefault());
        list.add(ChatTheme.buildDog());
        return list;
    }

    /** 按 id 取主题（未知 id 回退默认） */
    public static ChatTheme getById(String id) {
        for (ChatTheme t : getThemes()) {
            if (t.id.equals(id)) return t;
        }
        return ChatTheme.buildDefault();
    }

    /** 当前生效主题（各人本地偏好，默认 = 珊瑚恋语） */
    public static ChatTheme getCurrent(Context context) {
        PrefsManager prefs = new PrefsManager(context);
        String id = prefs.getThemeId();
        return getById(id == null ? ChatTheme.ID_DEFAULT : id);
    }

    /** 主题是否已解锁（共同等级判定） */
    public static boolean isUnlocked(ChatTheme theme, int level) {
        return level >= theme.unlockLevel;
    }

    /** 当前共享等级（内存镜像，0 = 尚未同步到快照） */
    public static int getCurrentLevel(Context context) {
        return AffectionStateHolder.getLevel();
    }

}
