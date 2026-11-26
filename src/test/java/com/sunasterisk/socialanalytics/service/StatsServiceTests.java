package com.sunasterisk.socialanalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sunasterisk.socialanalytics.messaging.StatsRefreshMessage;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class StatsServiceTests {

    /** 2026-03-03 08:00 in Vietnam. */
    private static final Instant NOW = Instant.parse("2026-03-03T01:00:00Z");

    private final DailyStatsRepository repository = mock(DailyStatsRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StatsService service = new StatsService(repository, jdbc,
            Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));

    @Test
    void recomputesEveryDayFromTheMessageToTodayWithVietnamDayBounds() {
        int days = service.refresh(StatsRefreshMessage.metricsChanged(LocalDate.of(2026, 3, 1)));

        assertThat(days).isEqualTo(3);
        verify(repository).recompute(LocalDate.of(2026, 3, 1), Instant.parse("2026-02-28T17:00:00Z"),
                Instant.parse("2026-03-01T17:00:00Z"), NOW);
        verify(repository).recompute(LocalDate.of(2026, 3, 3), Instant.parse("2026-03-02T17:00:00Z"),
                Instant.parse("2026-03-03T17:00:00Z"), NOW);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void aDayAfterTodayRecomputesTodayOnly() {
        assertThat(service.refresh(StatsRefreshMessage.metricsUpdated(1, LocalDate.of(2026, 3, 9)))).isEqualTo(1);
        verify(repository).recompute(LocalDate.of(2026, 3, 3), Instant.parse("2026-03-02T17:00:00Z"),
                Instant.parse("2026-03-03T17:00:00Z"), NOW);
    }

    @Test
    void anImportRecordsWhenItsStatisticsWereRefreshed() {
        service.refresh(StatsRefreshMessage.importCompleted(42, LocalDate.of(2026, 3, 3)));

        verify(jdbc).update(eq("update import_batches set stats_refreshed_at = :now where id = :id"),
                ArgumentMatchers.<MapSqlParameterSource>argThat(params ->
                        Long.valueOf(42).equals(params.getValue("id"))));
    }
}
