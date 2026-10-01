package com.eyemonitor.util;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

import okhttp3.OkHttpClient;

/**
 * Glide 网络栈替换：默认 HttpURLConnection 主机名校验严格（IP 直连会失败），
 * 换成 OkHttp + HostnamePolicy（与 REST/WS 同一套策略：IP 放开主机名、域名严格）。
 */
@GlideModule
public class EyeGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(Context context, Glide glide, Registry registry) {
        OkHttpClient client = new OkHttpClient.Builder()
                .hostnameVerifier(HostnamePolicy.verifier(context))
                .build();
        registry.replace(GlideUrl.class, InputStream.class,
                new OkHttpUrlLoader.Factory(client));
    }
}