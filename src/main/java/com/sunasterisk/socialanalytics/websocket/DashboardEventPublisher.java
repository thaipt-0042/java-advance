package com.sunasterisk.socialanalytics.websocket;

import com.sunasterisk.socialanalytics.config.WebSocketConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes {@link DashboardEvent}s to {@value WebSocketConfig#METRICS_TOPIC} once the transaction that published them
 * commits, so a browser that reacts at once reads the new data. A failure to push is logged; the change itself is
 * already saved and the next event (or a reload) shows it.
 */
@Component
public class DashboardEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DashboardEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public DashboardEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(DashboardEvent event) {
        try {
            messagingTemplate.convertAndSend(WebSocketConfig.METRICS_TOPIC, event);
            log.debug("Pushed {}", event);
        } catch (MessagingException ex) {
            log.warn("Could not push {} to {}", event, WebSocketConfig.METRICS_TOPIC, ex);
        }
    }
}
