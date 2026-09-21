package com.kh.game.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final TaskScheduler messageBrokerTaskScheduler;
    private final long heartbeatServerMs;
    private final long heartbeatClientMs;

    /**
     * STOMP 하트비트 — 네트워크가 조용히 끊긴 연결(모바일·절전)을 서버가 알아채게 한다. 창을 닫으면 TCP 가 바로 끊겨
     * 하트비트 없이도 알지만, 조용히 끊긴 연결은 이것이 없으면 수 분~수십 분 살아 있는 것으로 보인다 (RoomPresenceService).
     *
     * <p>{서버가 보내는 간격, 클라이언트에게 바라는 간격}. 클라이언트가 실제로 보내는 간격은 max(클라이언트 제안, 두 번째 값)이고
     * 서버는 그 3배 동안 아무것도 못 받으면 연결을 끊는다(spring-messaging SimpleBrokerMessageHandler).
     * 클라이언트 간격을 30초로 두는 이유: 크롬은 5분 넘게 가려진 탭의 타이머를 1분에 한 번으로 묶는다(WebSocket 은 예외가 아님).
     * 10초로 두면 서버 한도가 30초라 대기실을 뒤 탭에 둔 사람이 끊겨 방에서 나가게 된다. 30초면 한도 90초 > 1분.
     */
    public WebSocketConfig(WebSocketAuthInterceptor webSocketAuthInterceptor,
                           @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler,
                           @Value("${game.multi.ws-heartbeat-server-ms:10000}") long heartbeatServerMs,
                           @Value("${game.multi.ws-heartbeat-client-ms:30000}") long heartbeatClientMs) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
        this.heartbeatServerMs = heartbeatServerMs;
        this.heartbeatClientMs = heartbeatClientMs;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{heartbeatServerMs, heartbeatClientMs})
                .setTaskScheduler(messageBrokerTaskScheduler);
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
