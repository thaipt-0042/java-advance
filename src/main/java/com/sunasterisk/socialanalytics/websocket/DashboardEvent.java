package com.sunasterisk.socialanalytics.websocket;

import com.sunasterisk.socialanalytics.entity.RunStatus;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What is pushed on {@code /topic/metrics} ({@code docs/02-thiet-ke-api.md}, "WebSocket"). No figures: the page
 * fetches {@code /api/v1/chart-data} again (the same rules and permissions as any other read).
 */
public sealed interface DashboardEvent {

    String type();

    Instant occurredAt();

    /** The daily statistics of {@code [fromDate, toDate]} were recomputed. */
    record StatsUpdated(String type, Instant occurredAt, LocalDate fromDate, LocalDate toDate)
            implements DashboardEvent {

        public StatsUpdated(Instant occurredAt, LocalDate fromDate, LocalDate toDate) {
            this("STATS_UPDATED", occurredAt, fromDate, toDate);
        }
    }

    /** A run of the metrics job ended; the dashboard refreshes "Cập nhật lần cuối". */
    record JobFinished(String type, Instant occurredAt, long runId, RunStatus status, Instant finishedAt)
            implements DashboardEvent {

        public JobFinished(Instant occurredAt, long runId, RunStatus status, Instant finishedAt) {
            this("JOB_FINISHED", occurredAt, runId, status, finishedAt);
        }
    }
}
