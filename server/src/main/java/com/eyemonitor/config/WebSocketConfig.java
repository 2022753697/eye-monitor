package com.eyemonitor.config;

import com.eyemonitor.handler.EyeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置。
 * <p>
 * 注册 /ws/eye 端点，使用原生 WebSocket（非 STOMP）。
 * 允许跨域，方便局域网内多设备连接。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EyeWebSocketHandler eyeWebSocketHandler;

    public WebSocketConfig(EyeWebSocketHandler eyeWebSocketHandler) {
        this.eyeWebSocketHandler = eyeWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eyeWebSocketHandler, "/ws/eye")
                .setAllowedOrigins("*");
    }
}