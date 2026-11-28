package com.sunasterisk.socialanalytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository.DailyStats;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The daily statistics query on MySQL, with the example of {@code docs/cau-hoi-lam-ro.md} Q12. The database is
 * shared with other tests, so each check compares a day before and after this test's rows were added (each test
 * rolls back).
 */
@Import(DailyStatsRepository.class)
class DailyStatsRepositoryTests extends JpaRepositoryTestBase {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY_1 = LocalDate.of(2001, 1, 1);
    private static final LocalDate DAY_2 = DAY_1.plusDays(1);
    private static final LocalDate DAY_3 = DAY_1.plusDays(2);
    private static final Instant NOW = Instant.parse("2026-03-01T03:00:00Z");

    @Autowired
    private DailyStatsRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void eachDayCountsEveryPostWithItsLatestSnapshotUpToTheEndOfThatDay() {
        DailyStats before1 = recompute(DAY_1);
        DailyStats before2 = recompute(DAY_2);
        DailyStats before3 = recompute(DAY_3);
        String t = UUID.randomUUID().toString().substring(0, 8);
        // Accounts "acc-a" and "ACC-A" are one account for MySQL (utf8mb4_0900_ai_ci), as for the statistics.
        long a = post(t + "-a", "acc-a-" + t);
        long c = post(t + "-c", "ACC-A-" + t);
        long b = post(t + "-b", "acc-b-" + t);
        // Day 1 (last microsecond of the day in Vietnam for A): A 100, C 10 (same account and run: 1000 followers),
        // B 50. Day 2: only B, at 00:00 sharp, so it belongs to day 2. Day 3: A 120, B 70.
        snapshot(a, at(DAY_1, "23:59:59.999999"), 100, 1000);
        snapshot(c, at(DAY_1, "23:59:59.999999"), 10, 1000);
        snapshot(b, at(DAY_1, "09:00"), 50, 500);
        snapshot(b, at(DAY_2, "00:00"), 60, 510);
        snapshot(a, at(DAY_3, "09:00"), 120, 1010);
        snapshot(b, at(DAY_3, "09:00"), 70, 520);

        // Day 1: 100 + 10 + 50 likes; followers per account: 1000 + 500, not per post.
        assertThat(minus(recompute(DAY_1), before1)).isEqualTo(stats(DAY_1, 3, 3, 160, 1500));
        // Day 2: A and C keep their day-1 snapshots (no drop to 0); only B was measured that day.
        assertThat(minus(recompute(DAY_2), before2)).isEqualTo(stats(DAY_2, 3, 1, 170, 1510));
        // Day 3: account A's latest followers come from A's day-3 snapshot (C has none that day).
        assertThat(minus(recompute(DAY_3), before3)).isEqualTo(stats(DAY_3, 3, 2, 200, 1530));
    }

    @Test
    void recomputingTwiceGivesTheSameRowsAndFindFiltersThem() {
        String t = UUID.randomUUID().toString().substring(0, 8);
        snapshot(post(t, "acc-" + t), at(DAY_1, "10:00"), 5, 7);

        DailyStats first = recompute(DAY_1);
        DailyStats second = recompute(DAY_1);

        assertThat(second).isEqualTo(first);
        assertThat(repository.find(DAY_1, DAY_1, SocialPlatform.TWITTER)).containsExactly(first);
        assertThat(repository.find(DAY_1, DAY_1, SocialPlatform.FACEBOOK))
                .allSatisfy(row -> assertThat(row.platform()).isEqualTo(SocialPlatform.FACEBOOK));
        assertThat(repository.find(DAY_2, null, SocialPlatform.TWITTER))
                .allSatisfy(row -> assertThat(row.statDate()).isAfterOrEqualTo(DAY_2));
        assertThat(repository.find(null, DAY_1, null)).contains(first)
                .allSatisfy(row -> assertThat(row.statDate()).isBeforeOrEqualTo(DAY_1));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from daily_platform_stats where stat_date = ? and platform = 'TWITTER'",
                Long.class, DAY_1)).isEqualTo(1);
    }

    @Test
    void findLastBeforeGivesTheClosestEarlierRowOfThePlatform() {
        String t = UUID.randomUUID().toString().substring(0, 8);
        snapshot(post(t, "acc-" + t), at(DAY_1, "10:00"), 5, 7);
        DailyStats day1 = recompute(DAY_1);

        assertThat(repository.findLastBefore(DAY_1.plusDays(30), SocialPlatform.TWITTER))
                .hasValueSatisfying(row -> assertThat(row.statDate()).isAfterOrEqualTo(DAY_1));
        assertThat(repository.findLastBefore(DAY_1.plusDays(1), SocialPlatform.TWITTER)).contains(day1);
        assertThat(repository.findLastBefore(LocalDate.of(1990, 1, 1), SocialPlatform.TWITTER)).isEmpty();
    }

    /** Recomputes a day (Vietnam time) and returns its Twitter row, zeros when there is none. */
    private DailyStats recompute(LocalDate day) {
        repository.recompute(day, day.atStartOfDay(VIETNAM).toInstant(),
                day.plusDays(1).atStartOfDay(VIETNAM).toInstant(), NOW);
        return repository.find(day, day, SocialPlatform.TWITTER).stream().findFirst()
                .orElse(stats(day, 0, 0, 0, 0));
    }

    private long post(String externalId, String account) {
        jdbcTemplate.update("insert into posts (platform, external_id, account_name, created_at, updated_at) "
                + "values ('TWITTER', ?, ?, now(6), now(6))", externalId, account);
        return jdbcTemplate.queryForObject("select id from posts where platform = 'TWITTER' and external_id = ?",
                Long.class, externalId);
    }

    private void snapshot(long postId, Instant capturedAt, long likes, long followers) {
        jdbcTemplate.update("insert into social_metrics (post_id, likes, shares, comments, followers, captured_at, "
                        + "created_at, updated_at) values (?, ?, 1, 2, ?, ?, now(6), now(6))", postId, likes,
                followers, LocalDateTime.ofInstant(capturedAt, ZoneOffset.UTC));
    }

    private static Instant at(LocalDate day, String time) {
        return LocalDateTime.parse(day + "T" + time).atZone(VIETNAM).toInstant();
    }

    /** Shares are 1 and comments 2 per post in {@link #snapshot}. */
    private static DailyStats stats(LocalDate day, int tracked, int measured, long likes, long followers) {
        return new DailyStats(day, SocialPlatform.TWITTER, tracked, measured, likes, tracked, 2L * tracked,
                followers);
    }

    private static DailyStats minus(DailyStats after, DailyStats before) {
        return new DailyStats(after.statDate(), after.platform(), after.postsTracked() - before.postsTracked(),
                after.postsMeasured() - before.postsMeasured(), after.likes() - before.likes(),
                after.shares() - before.shares(), after.comments() - before.comments(),
                after.followers() - before.followers());
    }
}
