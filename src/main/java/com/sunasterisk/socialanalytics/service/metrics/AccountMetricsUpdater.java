package com.sunasterisk.socialanalytics.service.metrics;

import com.sunasterisk.socialanalytics.client.AccountMetrics;
import com.sunasterisk.socialanalytics.client.SocialMetricsClient;
import com.sunasterisk.socialanalytics.config.AsyncConfig;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository.Account;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository.AccountPost;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository.NewSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Updates one account in a task of its own ({@code @Async}): reads its posts, calls the social network, then
 * writes one snapshot per post in a short transaction. The API call happens outside any transaction, so a slow
 * network never holds a database connection.
 *
 * <p>A separate bean from {@link MetricUpdateService}: {@code @Async} only works through the Spring proxy, not on a
 * call inside the same class.
 */
@Service
public class AccountMetricsUpdater {

    private static final Logger log = LoggerFactory.getLogger(AccountMetricsUpdater.class);

    private final AccountPostRepository accountPostRepository;
    private final PostBulkRepository bulkRepository;
    private final Map<SocialPlatform, SocialMetricsClient> clients = new EnumMap<>(SocialPlatform.class);
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AccountMetricsUpdater(AccountPostRepository accountPostRepository, PostBulkRepository bulkRepository,
            List<SocialMetricsClient> clients, Clock clock, PlatformTransactionManager transactionManager) {
        this.accountPostRepository = accountPostRepository;
        this.bulkRepository = bulkRepository;
        clients.forEach(client -> this.clients.put(client.platform(), client));
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Never completes exceptionally: a failure (API error, database error) is returned as a failed result and
     * logged with the account and the run, so one account never stops the others.
     *
     * @param capturedAt {@code captured_at} of the snapshots: the start of the run
     */
    @Async(AsyncConfig.ACCOUNT_EXECUTOR)
    public CompletableFuture<AccountResult> update(long runId, Instant capturedAt, Account account) {
        try {
            return CompletableFuture.completedFuture(AccountResult.succeeded(account,
                    updateNow(capturedAt, account)));
        } catch (RuntimeException ex) {
            log.warn("Metrics job run {}: account {} failed: {}", runId, account, ex.toString());
            log.debug("Metrics job run {}: account {} failure", runId, account, ex);
            return CompletableFuture.completedFuture(AccountResult.failed(account, ex.getMessage()));
        }
    }

    private int updateNow(Instant capturedAt, Account account) {
        List<AccountPost> posts = accountPostRepository.findPosts(account);
        if (posts.isEmpty()) {
            return 0;
        }
        SocialMetricsClient client = clients.get(account.platform());
        if (client == null) {
            throw new IllegalStateException("No metrics client for " + account.platform());
        }
        AccountMetrics metrics = client.fetch(account.name(), posts.stream().map(AccountPost::externalId).toList());
        Integer written = transaction.execute(status -> {
            // Posts deleted since they were read are skipped instead of failing on the foreign key.
            Set<Long> existing = accountPostRepository.lockExisting(posts.stream().map(AccountPost::id).toList());
            List<NewSnapshot> snapshots = posts.stream()
                    .filter(post -> existing.contains(post.id()) && metrics.posts().containsKey(post.externalId()))
                    .map(post -> {
                        AccountMetrics.PostMetrics counts = metrics.posts().get(post.externalId());
                        return new NewSnapshot(post.id(), counts.likes(), counts.shares(), counts.comments(),
                                metrics.followers());
                    })
                    .toList();
            if (!snapshots.isEmpty()) {
                bulkRepository.insertSnapshots(snapshots, capturedAt, clock.instant());
            }
            return snapshots.size();
        });
        return written == null ? 0 : written;
    }
}
