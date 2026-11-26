package com.sunasterisk.socialanalytics.messaging;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Logs every message that failed all its deliveries ({@value StatsRefreshMessage#DEAD_LETTER_QUEUE}), with its
 * body: the statistics of those days stay stale until another message covers them. Reads the raw message, so even
 * a body that can't be converted is logged.
 */
@Component
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    @JmsListener(destination = StatsRefreshMessage.DEAD_LETTER_QUEUE)
    public void onMessage(Message message) throws JMSException {
        String body = message instanceof TextMessage text ? text.getText() : message.getClass().getSimpleName();
        // Artemis keeps the queue the message failed on in this property.
        log.error("Statistics refresh message moved to {} after failed deliveries (id {}, from {}): {}",
                StatsRefreshMessage.DEAD_LETTER_QUEUE, message.getJMSMessageID(),
                message.getStringProperty("_AMQ_ORIG_ADDRESS"), body);
    }
}
