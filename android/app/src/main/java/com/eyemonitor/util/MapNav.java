package com.eyemonitor.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.eyemonitor.R;

/**
 * 高德地图 App 一键导航：amapuri 深链（驾车模式），未安装时回退系统 geo: 地图。
 * SOS「确认」与地图页「去找他」共用。
 */
public final class MapNav {

    private MapNav() {}

    public static void navigate(Context ctx, double lat, double lng, String destName) {
        String name = destName != null && !destName.isEmpty() ? destName : "目标位置";
        String uri = "amapuri://route/plan/?dlat=" + lat + "&dlon=" + lng
                + "&dname=" + Uri.encode(name) + "&dev=0&t=0"; // t=0 驾车导航
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return;
        } catch (Exception ignored) {
            // 高德未安装等 → 回退系统地图
        }
        try {
            Intent geo = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng
                            + "(" + Uri.encode(name) + ")"));
            geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(geo);
        } catch (Exception e) {
            Toast.makeText(ctx, R.string.map_nav_not_found, Toast.LENGTH_SHORT).show();
        }
    }
}