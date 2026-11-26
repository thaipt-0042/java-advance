package com.sunasterisk.socialanalytics.client;

import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Mock of a social network API ({@code docs/cau-hoi-lam-ro.md} Q6): counts that grow over time, with a delay and a
 * failure rate set in {@code app.metrics-job.mock.*} to try the job's error handling.
 *
 * <p>Each count is {@code base + growth × hours since 2026-01-01 + bonus}, where base, growth (≥ 1 per hour) and the
 * bonus of each hour (below growth) are pseudo-random but derived from the post or account and the hour. So the
 * values look random, never go down from one hour to the next, are the same for two calls in the same hour, and
 * survive a restart (no state).
 */
public abstract class MockSocialMetricsClient implements SocialMetricsClient {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private final SocialPlatform platform;
    private final MetricsJobProperties.Mock settings;
    private final Clock clock;
    private final RandomGenerator random;

    protected MockSocialMetricsClient(SocialPlatform platform, MetricsJobProperties properties, Clock clock,
            RandomGenerator random) {
        this.platform = platform;
        this.settings = properties.mock();
        this.clock = clock;
        this.random = random;
    }

    @Override
    public SocialPlatform platform() {
        return platform;
    }

    @Override
    public AccountMetrics fetch(String accountName, Collection<String> externalIds) {
        sleep(settings.delay());
        if (random.nextDouble() < settings.failureRate()) {
            throw new SocialApiException("Mock %s API: simulated failure".formatted(platform));
        }
        long hour = Math.max(0, Duration.between(EPOCH, clock.instant()).toHours());
        Map<String, AccountMetrics.PostMetrics> posts = new LinkedHashMap<>();
        for (String externalId : externalIds) {
            String key = platform + "/" + externalId;
            posts.put(externalId, new AccountMetrics.PostMetrics(count(key + "/likes", hour, 500, 20),
                    count(key + "/shares", hour, 50, 3), count(key + "/comments", hour, 80, 5)));
        }
        return new AccountMetrics(count(platform + "/" + accountName + "/followers", hour, 20_000, 50), posts);
    }

    /** {@code base} in [0, maxBase), growth in [1, maxGrowth], hourly bonus in [0, growth). */
    static long count(String key, long hour, int maxBase, int maxGrowth) {
        long seed = mix(key.hashCode());
        long base = Math.floorMod(seed, maxBase);
        long growth = 1 + Math.floorMod(mix(seed), maxGrowth);
        long bonus = Math.floorMod(mix(seed ^ hour), growth);
        return base + growth * hour + bonus;
    }

    /** SplitMix64 finalizer: spreads similar keys / hours to unrelated values. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SocialApiException("Interrupted");
        }
    }
}
