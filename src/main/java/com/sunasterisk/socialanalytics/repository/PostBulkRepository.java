package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Bulk inserts for the Excel import, through JDBC batches. Ids are {@code AUTO_INCREMENT} (IDENTITY), so Hibernate
 * inserts entities one statement at a time; a batch sent with {@code rewriteBatchedStatements=true} (see
 * application.properties) becomes a few multi-row INSERTs instead of thousands of round trips.
 *
 * <p>Rows written here bypass JPA auditing, so the caller passes the timestamp for {@code created_at} /
 * {@code updated_at}. Must run inside the caller's transaction (same connection as JPA).
 */
@Repository
public class PostBulkRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PostBulkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts new posts (not managed by JPA, no id). Read their ids back with {@link #findIds}. */
    public void insertPosts(List<Post> posts, Instant now) {
        SqlParameterSource[] params = posts.stream()
                .map(post -> new MapSqlParameterSource()
                        .addValue("platform", post.getPlatform().name())
                        .addValue("externalId", post.getExternalId())
                        .addValue("accountName", post.getAccountName())
                        .addValue("url", post.getUrl())
                        .addValue("content", post.getContent())
                        .addValue("publishedAt", utc(post.getPublishedAt()))
                        .addValue("adCost", post.getAdCost())
                        .addValue("now", utc(now)))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into posts (platform, external_id, account_name, url, content, published_at, ad_cost,
                                   created_at, updated_at)
                values (:platform, :externalId, :accountName, :url, :content, :publishedAt, :adCost, :now, :now)
                """, params);
    }

    /** Ids of the posts of one platform among {@code externalIds} (compared with the column's collation). */
    public List<PostId> findIds(SocialPlatform platform, Collection<String> externalIds) {
        return jdbc.query("""
                        select id, external_id from posts
                        where platform = :platform and external_id in (:externalIds)
                        """,
                new MapSqlParameterSource().addValue("platform", platform.name())
                        .addValue("externalIds", externalIds),
                (rs, rowNum) -> new PostId(rs.getLong("id"), platform, rs.getString("external_id")));
    }

    public void insertSnapshots(List<NewSnapshot> snapshots, Instant capturedAt, Instant now) {
        SqlParameterSource[] params = snapshots.stream()
                .map(snapshot -> new MapSqlParameterSource()
                        .addValue("postId", snapshot.postId())
                        .addValue("likes", snapshot.likes())
                        .addValue("shares", snapshot.shares())
                        .addValue("comments", snapshot.comments())
                        .addValue("followers", snapshot.followers())
                        .addValue("capturedAt", utc(capturedAt))
                        .addValue("now", utc(now)))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into social_metrics (post_id, likes, shares, comments, followers, captured_at,
                                            created_at, updated_at)
                values (:postId, :likes, :shares, :comments, :followers, :capturedAt, :now, :now)
                """, params);
    }

    /**
     * The DATETIME columns hold UTC wall-clock time ({@code hibernate.jdbc.time_zone=UTC}). A {@code LocalDateTime}
     * is sent as is, whereas a {@code java.sql.Timestamp} would be shifted by the JVM / connection time zones.
     */
    private static @Nullable LocalDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record PostId(long id, SocialPlatform platform, String externalId) {
    }

    public record NewSnapshot(long postId, long likes, long shares, long comments, long followers) {
    }
}
