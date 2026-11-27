package com.sunasterisk.socialanalytics.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.client.AccountMetrics;
import com.sunasterisk.socialanalytics.client.AccountMetrics.PostMetrics;
import com.sunasterisk.socialanalytics.client.SocialApiException;
import com.sunasterisk.socialanalytics.client.SocialMetricsClient;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository.Account;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** One account on MySQL, with a scripted client instead of the mock API. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AccountMetricsUpdaterTests {

    private static final Instant CAPTURED_AT = Instant.parse("2026-11-20T03:00:00.123456Z");

    @Autowired
    private AccountPostRepository accountPostRepository;

    @Autowired
    private PostBulkRepository bulkRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Test
    void writesOneSnapshotPerPostWithTheAccountFollowers() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        long first = post(account, "1");
        long second = post(account, "2");
        AccountMetricsUpdater updater = updater((name, ids) -> new AccountMetrics(900,
                Map.of("1", new PostMetrics(10, 2, 3), "2", new PostMetrics(20, 4, 6))));

        AccountResult result = updater.update(1, CAPTURED_AT, new Account(SocialPlatform.TWITTER, account)).get();

        assertThat(result.isFailed()).isFalse();
        assertThat(result.snapshotsCreated()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("select post_id, likes, followers, captured_at from social_metrics "
                + "where post_id in (?, ?) order by post_id", first, second))
                .extracting(row -> List.of(row.get("post_id"), row.get("likes"), row.get("followers")))
                .containsExactly(List.of(first, 10L, 900L), List.of(second, 20L, 900L));
    }

    @Test
    void skipsAPostDeletedDuringTheCallAndOneTheApiDoesNotKnow() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        long kept = post(account, "kept");
        long deleted = post(account, "deleted");
        post(account, "unknown");
        AccountMetricsUpdater updater = updater((name, ids) -> {
            // Deleted while the API call runs (outside the transaction).
            jdbcTemplate.update("delete from posts where id = ?", deleted);
            return new AccountMetrics(5, Map.of("kept", new PostMetrics(1, 1, 1),
                    "deleted", new PostMetrics(1, 1, 1)));
        });

        AccountResult result = updater.update(1, CAPTURED_AT, new Account(SocialPlatform.TWITTER, account)).get();

        assertThat(result.isFailed()).isFalse();
        assertThat(result.snapshotsCreated()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("select post_id from social_metrics where post_id = ?", Long.class,
                kept)).hasSize(1);
    }

    @Test
    void anApiErrorFailsTheAccountWithoutWritingAnything() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        long only = post(account, "1");
        AccountMetricsUpdater updater = updater((name, ids) -> {
            throw new SocialApiException("rate limited");
        });

        AccountResult result = updater.update(1, CAPTURED_AT, new Account(SocialPlatform.TWITTER, account)).get();

        assertThat(result.isFailed()).isTrue();
        assertThat(result.error()).isEqualTo("rate limited");
        assertThat(jdbcTemplate.queryForObject("select count(*) from social_metrics where post_id = ?", Long.class,
                only)).isZero();
    }

    @Test
    void anAccountWithoutPostsOrClientIsHandled() throws Exception {
        AccountMetricsUpdater updater = updater((name, ids) -> {
            throw new AssertionError("not called");
        });

        assertThat(updater.update(1, CAPTURED_AT, new Account(SocialPlatform.TWITTER, "nobody-" + UUID.randomUUID()))
                .get().snapshotsCreated()).isZero();

        String account = "acc-" + UUID.randomUUID();
        jdbcTemplate.update("insert into posts (platform, external_id, account_name, created_at, updated_at) "
                + "values ('FACEBOOK', ?, ?, now(6), now(6))", account, account);
        AccountResult noClient = updater.update(1, CAPTURED_AT, new Account(SocialPlatform.FACEBOOK, account)).get();
        assertThat(noClient.isFailed()).isTrue();
        assertThat(noClient.error()).isEqualTo("No metrics client for FACEBOOK");
    }

    private long post(String account, String externalId) {
        jdbcTemplate.update("insert into posts (platform, external_id, account_name, created_at, updated_at) "
                + "values ('TWITTER', ?, ?, now(6), now(6))", account + "-" + externalId, account);
        return jdbcTemplate.queryForObject("select id from posts where platform = 'TWITTER' and external_id = ?",
                Long.class, account + "-" + externalId);
    }

    /** An updater whose only client is a Twitter one answering with {@code answer}, keyed by suffix. */
    private AccountMetricsUpdater updater(BiFunction<String, Collection<String>, AccountMetrics> answer) {
        SocialMetricsClient twitter = new SocialMetricsClient() {
            @Override
            public SocialPlatform platform() {
                return SocialPlatform.TWITTER;
            }

            @Override
            public AccountMetrics fetch(String accountName, Collection<String> externalIds) {
                AccountMetrics metrics = answer.apply(accountName, externalIds);
                // The scripted answers use the suffix after "<account>-"; map them back to the real ids.
                Map<String, PostMetrics> posts = new HashMap<>();
                metrics.posts().forEach((suffix, counts) -> posts.put(accountName + "-" + suffix, counts));
                return new AccountMetrics(metrics.followers(), posts);
            }
        };
        return new AccountMetricsUpdater(accountPostRepository, bulkRepository, List.of(twitter), clock,
                transactionManager);
    }
}
