package com.sunasterisk.socialanalytics.config;

import com.sunasterisk.socialanalytics.websocket.DashboardEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket at {@code /ws}, with SockJS so browsers behind proxies that block WebSocket fall back to
 * XHR streaming / polling or an iframe ({@code docs/03-kien-truc.md}, section 6). The broker is Spring's simple
 * in-memory broker on {@code /topic}: events reach the browsers connected to this instance only (one instance;
 * several would need an external STOMP broker).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Same origin only (the default): the page that connects is the dashboard of this app.
        registry.addEndpoint("/ws").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    /** The only destination clients subscribe to; see {@link DashboardEventPublisher}. */
    public static final String METRICS_TOPIC = "/topic/metrics";
}
