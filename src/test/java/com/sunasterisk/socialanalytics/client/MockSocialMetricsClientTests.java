package com.sunasterisk.socialanalytics.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class MockSocialMetricsClientTests {

    private static final Instant NOW = Instant.parse("2026-11-20T03:15:00Z");

    @Test
    void returnsEveryPostAndTheAccountFollowers() {
        AccountMetrics metrics = facebook(NOW, 0).fetch("page", List.of("a", "b"));

        assertThat(metrics.posts()).containsOnlyKeys("a", "b");
        assertThat(metrics.followers()).isPositive();
        assertThat(metrics.posts().get("a")).isNotEqualTo(metrics.posts().get("b"));
    }

    @Test
    void sameValuesWithinAnHourAndNeverLowerTheNextHours() {
        AccountMetrics first = facebook(NOW, 0).fetch("page", List.of("a"));
        assertThat(facebook(NOW.plus(Duration.ofMinutes(40)), 0).fetch("page", List.of("a"))).isEqualTo(first);

        AccountMetrics previous = first;
        for (int hour = 1; hour <= 200; hour++) {
            AccountMetrics next = facebook(NOW.plus(Duration.ofHours(hour)), 0).fetch("page", List.of("a"));
            assertThat(next.followers()).isGreaterThan(previous.followers());
            assertThat(next.posts().get("a").likes()).isGreaterThan(previous.posts().get("a").likes());
            assertThat(next.posts().get("a").shares()).isGreaterThan(previous.posts().get("a").shares());
            assertThat(next.posts().get("a").comments()).isGreaterThan(previous.posts().get("a").comments());
            previous = next;
        }
    }

    @Test
    void platformsGiveDifferentValuesForTheSameIds() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AccountMetrics facebook = new MockFacebookMetricsClient(properties(0), clock, fixed(0.5))
                .fetch("page", List.of("a"));
        AccountMetrics twitter = new MockTwitterMetricsClient(properties(0), clock, fixed(0.5))
                .fetch("page", List.of("a"));

        assertThat(twitter.posts().get("a")).isNotEqualTo(facebook.posts().get("a"));
    }

    @Test
    void failsAtTheConfiguredRate() {
        assertThatThrownBy(() -> client(NOW, 0.3, 0.29).fetch("page", List.of("a")))
                .isInstanceOf(SocialApiException.class)
                .hasMessage("Mock FACEBOOK API: simulated failure");
        assertThat(client(NOW, 0.3, 0.3).fetch("page", List.of("a")).posts()).hasSize(1);
        assertThat(client(NOW, 0, 0).fetch("page", List.of("a")).posts()).hasSize(1);
    }

    @Test
    void waitsTheConfiguredDelay() {
        MetricsJobProperties properties = new MetricsJobProperties("-", null, Duration.ofMinutes(5),
                Duration.ofMinutes(10), new MetricsJobProperties.Mock(Duration.ofMillis(120), 0));
        MockFacebookMetricsClient client = new MockFacebookMetricsClient(properties, Clock.fixed(NOW,
                ZoneOffset.UTC), fixed(0.5));

        long start = System.nanoTime();
        client.fetch("page", List.of("a"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(120));
    }

    @Test
    void anInterruptedCallFails() {
        MetricsJobProperties properties = new MetricsJobProperties("-", null, Duration.ofMinutes(5),
                Duration.ofMinutes(10), new MetricsJobProperties.Mock(Duration.ofSeconds(5), 0));
        MockFacebookMetricsClient client = new MockFacebookMetricsClient(properties, Clock.fixed(NOW,
                ZoneOffset.UTC), fixed(0.5));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.fetch("page", List.of("a")))
                    .isInstanceOf(SocialApiException.class).hasMessage("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static MockFacebookMetricsClient facebook(Instant now, double failureRate) {
        return client(now, failureRate, 0.99);
    }

    private static MockFacebookMetricsClient client(Instant now, double failureRate, double draw) {
        return new MockFacebookMetricsClient(properties(failureRate), Clock.fixed(now, ZoneOffset.UTC), fixed(draw));
    }

    private static MetricsJobProperties properties(double failureRate) {
        return new MetricsJobProperties("-", null, Duration.ofMinutes(5), Duration.ofMinutes(10),
                new MetricsJobProperties.Mock(Duration.ZERO, failureRate));
    }

    /** A generator whose {@code nextDouble()} is always {@code value}. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
