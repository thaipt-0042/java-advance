package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the report rows with JDBC: a post and its latest snapshot in one query (no entity is loaded, so a large
 * export doesn't fill the persistence context). Posts are read in pages by id (keyset), so each query stays cheap
 * however far the export has gone.
 */
@Repository
public class ReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Number of posts matching the filter. {@code publishedFrom} / {@code publishedBefore} bound
     * {@code published_at} to {@code [from, before)}; {@code null} skips a bound (and a post without
     * {@code published_at} only matches when both are null).
     */
    public long countPosts(@Nullable SocialPlatform platform, @Nullable Instant publishedFrom,
            @Nullable Instant publishedBefore) {
        Long count = jdbc.queryForObject("select count(*) from posts p where " + FILTER,
                filter(platform, publishedFrom, publishedBefore), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Next page of posts with an id greater than {@code afterId}, by id, each with its latest snapshot. The page of
     * posts is chosen first, with the time of each one's latest snapshot ({@code max()}, answered from the
     * {@code UNIQUE (post_id, captured_at)} index); then that one snapshot row is read. Measured on 10,000 posts with
     * 300 snapshots each: 3.5 s for all pages, against 9-11 s with {@code LATERAL ... LIMIT 1}, which MySQL
     * re-materializes for every post.
     */
    public List<ReportEntry> findPage(@Nullable SocialPlatform platform, @Nullable Instant publishedFrom,
            @Nullable Instant publishedBefore, long afterId, int limit) {
        return jdbc.query("""
                        select p.id, p.platform, p.external_id, p.account_name, p.url, p.content, p.published_at,
                               m.likes, m.shares, m.comments, m.followers, m.captured_at
                        from (
                            select p.id, p.platform, p.external_id, p.account_name, p.url, p.content,
                                   p.published_at,
                                   (select max(s.captured_at) from social_metrics s where s.post_id = p.id)
                                       as latest_at
                            from posts p
                            where p.id > :afterId and """ + FILTER + """
                            order by p.id
                            limit :limit
                        ) p
                        left join social_metrics m on m.post_id = p.id and m.captured_at = p.latest_at
                        order by p.id
                        """,
                filter(platform, publishedFrom, publishedBefore).addValue("afterId", afterId).addValue("limit", limit),
                (rs, rowNum) -> entry(rs));
    }

    private static final String FILTER = """
            (:platform is null or p.platform = :platform)
              and (:publishedFrom is null or p.published_at >= :publishedFrom)
              and (:publishedBefore is null or p.published_at < :publishedBefore)
            """;

    private static MapSqlParameterSource filter(@Nullable SocialPlatform platform, @Nullable Instant publishedFrom,
            @Nullable Instant publishedBefore) {
        return new MapSqlParameterSource()
                .addValue("platform", platform == null ? null : platform.name())
                .addValue("publishedFrom", utc(publishedFrom))
                .addValue("publishedBefore", utc(publishedBefore));
    }

    private static ReportEntry entry(ResultSet rs) throws SQLException {
        return new ReportEntry(rs.getLong("id"), SocialPlatform.valueOf(rs.getString("platform")),
                rs.getString("external_id"), rs.getString("account_name"), rs.getString("url"),
                rs.getString("content"), instant(rs, "published_at"), rs.getObject("likes", Long.class),
                rs.getObject("shares", Long.class), rs.getObject("comments", Long.class),
                rs.getObject("followers", Long.class), instant(rs, "captured_at"));
    }

    /** DATETIME columns hold UTC wall-clock time (see {@code PostBulkRepository}). */
    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static @Nullable LocalDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * A post and its latest snapshot, as read: the metric columns are null when the post has none. The post id is
     * the keyset for the next page.
     */
    public record ReportEntry(long postId, SocialPlatform platform, String externalId, String accountName,
            @Nullable String url, @Nullable String content, @Nullable Instant publishedAt, @Nullable Long likes,
            @Nullable Long shares, @Nullable Long comments, @Nullable Long followers, @Nullable Instant capturedAt) {
    }
}
