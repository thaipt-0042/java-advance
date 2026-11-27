package com.sunasterisk.socialanalytics.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends a {@link StatsRefreshMessage} to the queue once the transaction that published it has committed: a rolled
 * back change sends nothing, and the listener never reads data that isn't committed yet. Published outside a
 * transaction, it is sent at once.
 *
 * <p>If the broker can't be reached, the change is already committed: the error is logged, not thrown at the user.
 * The statistics then lag until the next message covering those days (the hourly job recomputes today).
 */
@Component
public class StatsRefreshPublisher {

    private static final Logger log = LoggerFactory.getLogger(StatsRefreshPublisher.class);

    private final JmsTemplate jmsTemplate;

    public StatsRefreshPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void send(StatsRefreshMessage message) {
        try {
            jmsTemplate.convertAndSend(StatsRefreshMessage.QUEUE, message);
            log.debug("Sent {}", message);
        } catch (JmsException ex) {
            log.error("Could not send {} to {}; statistics from {} are stale until the next refresh", message,
                    StatsRefreshMessage.QUEUE, message.fromDate(), ex);
        }
    }
}
