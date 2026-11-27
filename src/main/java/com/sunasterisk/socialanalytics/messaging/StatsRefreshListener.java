package com.sunasterisk.socialanalytics.messaging;

import com.sunasterisk.socialanalytics.service.StatsService;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Recomputes the statistics for each {@link StatsRefreshMessage}. One consumer ({@code concurrency = "1"}): two
 * refreshes never run at the same time. A failure throws, so the broker delivers the message again and finally
 * moves it to the dead letter queue; recomputing is idempotent, so a repeated delivery is harmless.
 */
@Component
public class StatsRefreshListener {

    private final StatsService statsService;

    public StatsRefreshListener(StatsService statsService) {
        this.statsService = statsService;
    }

    @JmsListener(destination = StatsRefreshMessage.QUEUE, concurrency = "1")
    public void onMessage(StatsRefreshMessage message) {
        statsService.refresh(message);
    }
}
