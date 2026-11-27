package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.messaging.StatsRefreshMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tells the statistics which days changed ({@code docs/cau-hoi-lam-ro.md} Q5): publishes a
 * {@link StatsRefreshMessage} event in the current transaction, sent to the queue after commit. The day is the
 * earliest one affected, in the application time zone; the listener recomputes from there to today.
 */
@Component
public class StatsChanges {

    private final ApplicationEventPublisher events;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public StatsChanges(ApplicationEventPublisher events, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** An import committed; {@code changedPostIds}: existing posts whose account changed (their past changes too). */
    public void importCompleted(long batchId, Instant importedAt, Collection<Long> changedPostIds) {
        LocalDate from = dayOf(importedAt);
        Instant earliest = earliestSnapshot(changedPostIds);
        if (earliest != null && dayOf(earliest).isBefore(from)) {
            from = dayOf(earliest);
        }
        events.publishEvent(StatsRefreshMessage.importCompleted(batchId, from));
    }

    /** A job run wrote snapshots captured at {@code startedAt}. */
    public void metricsUpdated(long runId, Instant startedAt) {
        events.publishEvent(StatsRefreshMessage.metricsUpdated(runId, dayOf(startedAt)));
    }

    /** Snapshots were created, moved or deleted through the API at these times (old and new). */
    public void snapshotsChanged(Instant... capturedAts) {
        Arrays.stream(capturedAts).filter(Objects::nonNull).min(Instant::compareTo)
                .ifPresent(earliest -> events.publishEvent(StatsRefreshMessage.metricsChanged(dayOf(earliest))));
    }

    /**
     * A post's account changed, or the post is about to be deleted: every day since its first snapshot changes
     * (nothing when it has none). Call before deleting, in the same transaction.
     */
    public void postChanged(long postId) {
        Instant earliest = earliestSnapshot(List.of(postId));
        if (earliest != null) {
            events.publishEvent(StatsRefreshMessage.metricsChanged(dayOf(earliest)));
        }
    }

    private @Nullable Instant earliestSnapshot(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return null;
        }
        LocalDateTime earliest = jdbc.queryForObject(
                "select min(captured_at) from social_metrics where post_id in (:ids)",
                new MapSqlParameterSource("ids", postIds), LocalDateTime.class);
        return earliest == null ? null : earliest.toInstant(ZoneOffset.UTC);
    }

    private LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, clock.getZone());
    }
}
