package com.eyemonitor.util;

import android.content.Context;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;

/**
 * 头像加载封装（Glide 圆形变换）。
 * <p>
 * avatar 为服务器相对路径（如 avatar/12.jpg）；null/空或以 http 开头按原样处理；
 * 加载失败或无头像时回退性别默认头像（avatar_female / avatar_male）。
 */
public final class AvatarUtils {

    private AvatarUtils() {}

    public static void loadInto(ImageView imageView, @Nullable String avatarPath, boolean female) {
        Context context = imageView.getContext();
        int fallback = female ? R.drawable.avatar_female : R.drawable.avatar_male;
        if (avatarPath == null || avatarPath.isEmpty()) {
            imageView.setImageResource(fallback);
            return;
        }
        String url = avatarPath;
        if (!avatarPath.startsWith("http://") && !avatarPath.startsWith("https://")) {
            url = new PrefsManager(context).getApiBaseUrl() + "/" + avatarPath;
        }
        Glide.with(context)
                .load(url)
                .apply(new RequestOptions()
                        .centerCrop()
                        .circleCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(fallback)
                        .error(fallback))
                .into(imageView);
    }
}