package com.sunasterisk.socialanalytics.security;

import com.sunasterisk.socialanalytics.config.WebSocketConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

/**
 * Security of the STOMP messages ({@code docs/03-kien-truc.md}, section 3). The HTTP handshake at {@code /ws/**}
 * needs a login (web chain). {@code @EnableWebSocketSecurity} then checks every frame:
 *
 * <ul>
 *   <li>{@code CONNECT} must carry the CSRF token in the {@code X-XSRF-TOKEN} native header, in its masked form
 *       (the {@code _csrf} meta tag of the dashboard), checked against the token of the handshake's session.
 *       The HTTP-level CSRF check is off for {@code /ws/**} because SockJS can't add a header to its XHR posts;
 *       this frame check is what protects the connection.</li>
 *   <li>Only logged-in users may connect and subscribe, and only to {@value WebSocketConfig#METRICS_TOPIC}. Clients
 *       send nothing else: any other message is denied.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return messages
                .simpTypeMatchers(SimpMessageType.CONNECT, SimpMessageType.HEARTBEAT, SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.DISCONNECT).authenticated()
                .simpSubscribeDestMatchers(WebSocketConfig.METRICS_TOPIC).authenticated()
                .anyMessage().denyAll()
                .build();
    }
}
