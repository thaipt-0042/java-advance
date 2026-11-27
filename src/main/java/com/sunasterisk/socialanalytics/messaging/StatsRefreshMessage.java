package com.sunasterisk.socialanalytics.messaging;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Body of a message on {@value #QUEUE}: recompute the statistics from {@code fromDate} (a day in the application
 * time zone) to today. Only ids and a date: no post data, no token. Also published as a Spring event inside the
 * transaction that made the change; {@link StatsRefreshPublisher} sends it once that transaction commits.
 *
 * @param batchId the import ({@link StatsRefreshType#IMPORT_COMPLETED})
 * @param runId the job run ({@link StatsRefreshType#METRICS_UPDATED})
 */
public record StatsRefreshMessage(StatsRefreshType type, LocalDate fromDate, @Nullable Long batchId,
        @Nullable Long runId) {

    public static final String QUEUE = "analytics.stats-refresh";
    public static final String DEAD_LETTER_QUEUE = "analytics.stats-refresh.dlq";

    public static StatsRefreshMessage importCompleted(long batchId, LocalDate fromDate) {
        return new StatsRefreshMessage(StatsRefreshType.IMPORT_COMPLETED, fromDate, batchId, null);
    }

    public static StatsRefreshMessage metricsUpdated(long runId, LocalDate fromDate) {
        return new StatsRefreshMessage(StatsRefreshType.METRICS_UPDATED, fromDate, null, runId);
    }

    public static StatsRefreshMessage metricsChanged(LocalDate fromDate) {
        return new StatsRefreshMessage(StatsRefreshType.METRICS_CHANGED, fromDate, null, null);
    }
}
