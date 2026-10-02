package com.eyemonitor.util;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.eyemonitor.R;

/**
 * 空态工具（UI 改造 v2）：把组件化空态接入列表页，替换裸 TextView。
 * <p>
 * 用法：在页面布局的列表容器外套一层 FrameLayout，include component_empty_state；
 * 在无数据时调用 {@link #show(Activity, int, int, int, int, int, View.OnClickListener)}。
 * 仅新增视觉工具，不进入业务逻辑。
 */
public final class EmptyStateUtil {

    private EmptyStateUtil() {}

    /**
     * 显示空态。
     *
     * @param emptyViewId 空态容器根视图 id（component_empty_state 的 include id）
     * @param iconRes     图标资源
     * @param titleRes    主标题字符串资源
     * @param subRes      副文案字符串资源（0=无）
     * @param actionRes   主按钮文案（0=无按钮）
     * @param onClick     主按钮回调（可空）
     */
    public static void show(Activity activity, int emptyViewId, int iconRes, int titleRes,
                            int subRes, int actionRes, View.OnClickListener onClick) {
        View root = activity.findViewById(emptyViewId);
        if (root == null) return;
        ImageView icon = root.findViewById(R.id.es_icon);
        if (icon != null) icon.setImageResource(iconRes);
        TextView title = root.findViewById(R.id.es_title);
        if (title != null) title.setText(titleRes);
        TextView sub = root.findViewById(R.id.es_subtitle);
        if (sub != null) {
            if (subRes != 0) {
                sub.setText(subRes);
                sub.setVisibility(View.VISIBLE);
            } else {
                sub.setVisibility(View.GONE);
            }
        }
        View action = root.findViewById(R.id.es_action);
        if (action != null) {
            if (actionRes != 0) {
                ((TextView) action).setText(actionRes);
                action.setVisibility(View.VISIBLE);
                action.setOnClickListener(onClick);
            } else {
                action.setVisibility(View.GONE);
            }
        }
        root.setVisibility(View.VISIBLE);
    }

    /** 隐藏空态 */
    public static void hide(Activity activity, int emptyViewId) {
        View root = activity.findViewById(emptyViewId);
        if (root != null) root.setVisibility(View.GONE);
    }

    /** 切换列表与空态（列表有数据时隐藏空态，空时显示） */
    public static void toggle(Activity activity, int emptyViewId, int listViewId, boolean hasData,
                              int iconRes, int titleRes, int subRes, int actionRes,
                              View.OnClickListener onClick) {
        View list = activity.findViewById(listViewId);
        if (list != null) list.setVisibility(hasData ? View.VISIBLE : View.GONE);
        if (hasData) {
            hide(activity, emptyViewId);
        } else {
            show(activity, emptyViewId, iconRes, titleRes, subRes, actionRes, onClick);
        }
    }

    /** 移除某个已填充的空态子视图（若在代码里动态 build） */
    public static void remove(ViewGroup parent, View view) {
        if (parent != null && view != null) parent.removeView(view);
    }
}
