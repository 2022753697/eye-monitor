package com.eyemonitor.config;

import com.eyemonitor.handler.EyeWebSocketHandler;
import com.eyemonitor.security.WsAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置。
 * <p>
 * 注册 /ws/eye 端点，使用原生 WebSocket（非 STOMP），允许跨域。
 * 握手前经 WsAuthInterceptor 校验 ?token=，通过后把 userId 写入 session attributes。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EyeWebSocketHandler eyeWebSocketHandler;
    private final WsAuthInterceptor wsAuthInterceptor;

    public WebSocketConfig(EyeWebSocketHandler eyeWebSocketHandler, WsAuthInterceptor wsAuthInterceptor) {
        this.eyeWebSocketHandler = eyeWebSocketHandler;
        this.wsAuthInterceptor = wsAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eyeWebSocketHandler, "/ws/eye")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOrigins("*");
    }
}