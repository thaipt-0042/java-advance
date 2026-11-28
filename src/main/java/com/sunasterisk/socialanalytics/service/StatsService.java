package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.messaging.StatsRefreshMessage;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository;
import com.sunasterisk.socialanalytics.util.DbTime;
import com.sunasterisk.socialanalytics.websocket.DashboardEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes {@code daily_platform_stats} for every day from a message's {@code fromDate} to today, in one
 * transaction ({@code docs/01-thiet-ke-csdl.md}). Day bounds are computed here in the application time zone and
 * passed to SQL as UTC instants (no {@code CONVERT_TZ}, which needs MySQL's time zone tables).
 *
 * <p>READ COMMITTED: under REPEATABLE READ, MySQL's {@code INSERT ... SELECT} takes shared locks on every
 * {@code posts} / {@code social_metrics} row it reads, blocking (and deadlocking with) the API, the import and the
 * job while it runs. Under READ COMMITTED the SELECT is a consistent read without locks.
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final DailyStatsRepository dailyStatsRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StatsService(DailyStatsRepository dailyStatsRepository, NamedParameterJdbcTemplate jdbc,
            ApplicationEventPublisher events, Clock clock) {
        this.dailyStatsRepository = dailyStatsRepository;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Recomputes {@code [fromDate, today]} and, for an import, records when its statistics were refreshed. A
     * {@code fromDate} after today (a clock ahead elsewhere) recomputes today only.
     *
     * @return the number of days recomputed
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int refresh(StatsRefreshMessage message) {
        long start = System.nanoTime();
        LocalDate today = LocalDate.now(clock);
        LocalDate from = message.fromDate().isAfter(today) ? today : message.fromDate();
        Instant now = DbTime.toColumnPrecision(clock.instant());
        int days = 0;
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            dailyStatsRepository.recompute(day, startOf(day), startOf(day.plusDays(1)), now);
            days++;
        }
        if (message.batchId() != null) {
            jdbc.update("update import_batches set stats_refreshed_at = :now where id = :id",
                    new MapSqlParameterSource().addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                            .addValue("id", message.batchId()));
        }
        // Pushed to the dashboards after commit (#13).
        events.publishEvent(new DashboardEvent.StatsUpdated(now, from, today));
        log.info("Recomputed statistics {}..{} ({} days, {} ms) for {}", from, today, days,
                (System.nanoTime() - start) / 1_000_000, message);
        return days;
    }

    private Instant startOf(LocalDate day) {
        return day.atStartOfDay(clock.getZone()).toInstant();
    }
}
