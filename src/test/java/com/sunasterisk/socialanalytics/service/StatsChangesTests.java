package com.sunasterisk.socialanalytics.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.messaging.StatsRefreshMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class StatsChangesTests {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StatsChanges changes = new StatsChanges(events, jdbc,
            Clock.fixed(Instant.parse("2026-03-05T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));

    @Test
    void daysAreInTheApplicationTimeZone() {
        // 17:00 UTC on 03-01 is 00:00 on 03-02 in Vietnam
        changes.metricsUpdated(5, Instant.parse("2026-03-01T17:00:00Z"));

        verify(events).publishEvent(StatsRefreshMessage.metricsUpdated(5, LocalDate.of(2026, 3, 2)));
    }

    @Test
    void aMovedSnapshotChangesFromTheEarlierOfItsTwoDays() {
        changes.snapshotsChanged(Instant.parse("2026-03-04T03:00:00Z"), Instant.parse("2026-02-20T03:00:00Z"));

        verify(events).publishEvent(StatsRefreshMessage.metricsChanged(LocalDate.of(2026, 2, 20)));
    }

    @Test
    void anImportStartsAtTheFirstSnapshotOfAPostThatChangedAccount() {
        earliestSnapshot(LocalDateTime.of(2026, 1, 10, 3, 0));

        changes.importCompleted(9, Instant.parse("2026-03-05T03:00:00Z"), List.of(1L, 2L));

        verify(events).publishEvent(StatsRefreshMessage.importCompleted(9, LocalDate.of(2026, 1, 10)));
    }

    @Test
    void anImportWithoutAccountChangeStartsToday() {
        changes.importCompleted(9, Instant.parse("2026-03-05T03:00:00Z"), List.of());

        verify(events).publishEvent(StatsRefreshMessage.importCompleted(9, LocalDate.of(2026, 3, 5)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void aPostWithoutSnapshotsChangesNothing() {
        earliestSnapshot(null);

        changes.postChanged(3);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void aPostChangesEveryDaySinceItsFirstSnapshot() {
        earliestSnapshot(LocalDateTime.of(2026, 2, 1, 18, 0));

        changes.postChanged(3);

        // 18:00 UTC on 02-01 is 01:00 on 02-02 in Vietnam
        verify(events).publishEvent(StatsRefreshMessage.metricsChanged(LocalDate.of(2026, 2, 2)));
    }

    private void earliestSnapshot(LocalDateTime utc) {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(LocalDateTime.class)))
                .thenReturn(utc);
    }
}
