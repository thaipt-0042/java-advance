package com.sunasterisk.socialanalytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

@ExtendWith(OutputCaptureExtension.class)
class StatsRefreshPublisherTests {

    @Test
    void anUnreachableBrokerIsLoggedNotThrownAtTheCommittedChange(CapturedOutput output) {
        JmsTemplate jmsTemplate = mock(JmsTemplate.class);
        doThrow(new UncategorizedJmsException("broker down")).when(jmsTemplate)
                .convertAndSend(eq(StatsRefreshMessage.QUEUE), any(Object.class));

        assertThatCode(() -> new StatsRefreshPublisher(jmsTemplate)
                .send(StatsRefreshMessage.metricsChanged(LocalDate.of(2026, 3, 1)))).doesNotThrowAnyException();

        assertThat(output.getOut()).contains("Could not send").contains("statistics from 2026-03-01 are stale");
    }
}
