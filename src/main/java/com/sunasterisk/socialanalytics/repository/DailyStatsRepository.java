package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code daily_platform_stats} (JDBC): recomputed from {@code posts} + {@code social_metrics}, never added to, so
 * recomputing a day twice gives the same rows (a message received twice is harmless).
 */
@Repository
public class DailyStatsRepository {

    /**
     * One row per platform for a day: each post with its latest snapshot before the end of the day (Q12); followers
     * summed per account (each account's latest value), not per post, since every post of an account repeats it.
     * Partitions on {@code account_name} use the column's collation, like everywhere else.
     *
     * <p>The latest snapshot of each post is found through the {@code UNIQUE (post_id, captured_at)} index: a
     * {@code max()} per post that MySQL answers from the index, then a lookup of that one row. The cost of a day
     * grows with the number of posts, not with the whole history of snapshots.
     */
    private static final String RECOMPUTE = """
            insert into daily_platform_stats (stat_date, platform, posts_tracked, posts_measured, likes, shares,
                                              comments, followers, refreshed_at)
            with latest as (
                select p.id as post_id, p.platform, p.account_name,
                       (select max(s.captured_at) from social_metrics s
                        where s.post_id = p.id and s.captured_at < :dayEnd) as captured_at
                from posts p
            ), per_post as (
                select l.platform, l.account_name, m.likes, m.shares, m.comments, m.followers, m.captured_at
                from latest l
                join social_metrics m on m.post_id = l.post_id and m.captured_at = l.captured_at
            ), per_account as (
                select platform, followers,
                       row_number() over (partition by platform, account_name order by captured_at desc)
                           as account_rank
                from per_post
            ), account_followers as (
                select platform, sum(followers) as followers from per_account where account_rank = 1
                group by platform
            )
            select :day, pp.platform, count(*),
                   sum(case when pp.captured_at >= :dayStart then 1 else 0 end),
                   sum(pp.likes), sum(pp.shares), sum(pp.comments), af.followers, :now
            from per_post pp
            join account_followers af on af.platform = pp.platform
            group by pp.platform, af.followers
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DailyStatsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces the rows of {@code day}, whose bounds in UTC are {@code [dayStart, dayEnd)}. Must run in a
     * transaction, so readers never see the day without rows.
     */
    public void recompute(LocalDate day, Instant dayStart, Instant dayEnd, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("day", day)
                .addValue("dayStart", utc(dayStart))
                .addValue("dayEnd", utc(dayEnd))
                .addValue("now", utc(now));
        jdbc.update("delete from daily_platform_stats where stat_date = :day", params);
        jdbc.update(RECOMPUTE, params);
    }

    /** Rows of {@code [from, to]} (both optional), by day then platform. */
    public List<DailyStats> find(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable SocialPlatform platform) {
        return jdbc.query("""
                        select stat_date, platform, posts_tracked, posts_measured, likes, shares, comments, followers
                        from daily_platform_stats
                        where (:from is null or stat_date >= :from)
                          and (:to is null or stat_date <= :to)
                          and (:platform is null or platform = :platform)
                        order by stat_date, platform
                        """,
                new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("platform", platform == null ? null : platform.name()),
                (rs, rowNum) -> new DailyStats(rs.getObject("stat_date", LocalDate.class),
                        SocialPlatform.valueOf(rs.getString("platform")), rs.getInt("posts_tracked"),
                        rs.getInt("posts_measured"), rs.getLong("likes"), rs.getLong("shares"),
                        rs.getLong("comments"), rs.getLong("followers")));
    }

    /** DATETIME columns hold UTC wall-clock time (see {@code PostBulkRepository}). */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record DailyStats(LocalDate statDate, SocialPlatform platform, int postsTracked, int postsMeasured,
            long likes, long shares, long comments, long followers) {
    }
}
