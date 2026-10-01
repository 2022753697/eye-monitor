package com.eyemonitor.util;

import android.content.Context;

import com.eyemonitor.config.PrefsManager;

import okhttp3.HttpUrl;
import okhttp3.internal.tls.OkHostnameVerifier;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

/**
 * 主机名校验策略（IP 直连兼容层）。
 * <p>
 * 背景：服务器证书是域名证书（如 twoy.online），当 App 以公网 IP 直连时，
 * 证书主机名校验必然失败（域名证书盖不住 IP）。策略：
 * - 服务器地址为 <b>IP</b>：放开主机名校验（仅这一环；TLS 加密与证书链信任仍严格，
 *   中间人无法用自签/非法证书冒充——证书必须是系统信任 CA 签发）
 * - 服务器地址为 <b>域名</b>：保持 OkHttp 默认严格校验（hostname 必须匹配 SAN）
 */
public final class HostnamePolicy {

    private HostnamePolicy() {}

    /** 动态校验器：每次握手读取当前服务器地址判断严格/宽松（用于常驻单例客户端，地址可热改） */
    public static HostnameVerifier verifier(Context context) {
        return (hostname, session) ->
                isIpAddress(hostOf(new PrefsManager(context).getServerUrl()))
                        || OkHostnameVerifier.INSTANCE.verify(hostname, session);
    }

    /** 按固定地址构造校验器（用于按连接重建的客户端，如 WebSocket） */
    public static HostnameVerifier verifierFor(String serverUrl) {
        return (hostname, session) ->
                isIpAddress(hostOf(serverUrl))
                        || OkHostnameVerifier.INSTANCE.verify(hostname, session);
    }

    private static String hostOf(String serverUrl) {
        try {
            HttpUrl url = HttpUrl.parse(serverUrl);
            return url == null ? "" : url.host();
        } catch (Exception e) {
            return "";
        }
    }

    /** 纯 IP 字面量判断（IPv4 四段 / IPv6 含冒号） */
    private static boolean isIpAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.contains(":")) return true; // IPv6
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        for (String seg : parts) {
            if (seg.isEmpty() || seg.length() > 3) return false;
            for (char c : seg.toCharArray()) {
                if (!Character.isDigit(c)) return false;
            }
        }
        return true;
    }
}