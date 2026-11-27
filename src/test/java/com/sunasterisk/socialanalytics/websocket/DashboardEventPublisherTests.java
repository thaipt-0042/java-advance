package com.sunasterisk.socialanalytics.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sunasterisk.socialanalytics.entity.RunStatus;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(OutputCaptureExtension.class)
class DashboardEventPublisherTests {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final DashboardEventPublisher publisher = new DashboardEventPublisher(template);

    @Test
    void sendsEventsToTheMetricsTopic() {
        DashboardEvent stats = new DashboardEvent.StatsUpdated(NOW, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 20));
        DashboardEvent job = new DashboardEvent.JobFinished(NOW, 7, RunStatus.PARTIAL, NOW);

        publisher.publish(stats);
        publisher.publish(job);

        verify(template).convertAndSend("/topic/metrics", stats);
        verify(template).convertAndSend("/topic/metrics", job);
        assertThat(stats.type()).isEqualTo("STATS_UPDATED");
        assertThat(job.type()).isEqualTo("JOB_FINISHED");
    }

    @Test
    void aBrokerFailureIsLoggedNotThrown(CapturedOutput output) {
        doThrow(new MessageDeliveryException("broker stopped")).when(template).convertAndSend(anyString(),
                any(Object.class));

        assertThatCode(() -> publisher.publish(new DashboardEvent.JobFinished(NOW, 7, RunStatus.FAILED, NOW)))
                .doesNotThrowAnyException();
        assertThat(output.getOut()).contains("Could not push");
    }
}
