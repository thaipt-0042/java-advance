package com.sunasterisk.socialanalytics.config;

import com.sunasterisk.socialanalytics.messaging.StatsRefreshMessage;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.JacksonJsonMessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

/**
 * JMS messages are JSON text ({@code docs/03-kien-truc.md}, section 5). The Java type travels as a short alias in
 * the {@code _type} property, mapped here, never as a class name: a message can only become one of these types.
 * Listeners use a transacted session (Spring Boot's default without a JTA transaction manager): an exception rolls
 * the message back and the broker delivers it again, then moves it to the dead letter queue
 * ({@code docker/artemis/broker.properties}).
 */
@Configuration
public class JmsConfig {

    static final String TYPE_PROPERTY = "_type";

    @Bean
    MessageConverter jmsMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName(TYPE_PROPERTY);
        converter.setTypeIdMappings(Map.of("stats-refresh", StatsRefreshMessage.class));
        return converter;
    }
}
