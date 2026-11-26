package com.sunasterisk.socialanalytics.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.messaging.StatsRefreshMessage;
import java.time.Duration;
import org.springframework.jms.core.JmsTemplate;

/**
 * The statistics queue is shared by every test and handled one message at a time (exclusive queue). Tests that
 * edit old snapshots leave refreshes of hundreds of days behind them (seconds each on a slow CI machine), so a test
 * waiting for its own message first lets those go, then times only its own.
 */
public final class StatsQueue {

    private StatsQueue() {
    }

    public static void awaitDrained(JmsTemplate jmsTemplate) throws InterruptedException {
        long start = System.nanoTime();
        while (Boolean.TRUE.equals(jmsTemplate.browse(StatsRefreshMessage.QUEUE,
                (session, browser) -> browser.getEnumeration().hasMoreElements()))) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("statistics queue drained")
                    .isLessThan(Duration.ofMinutes(5));
            Thread.sleep(200);
        }
    }
}
