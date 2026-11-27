package com.sunasterisk.socialanalytics.seed;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialMetric;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.SocialMetricRepository;
import com.sunasterisk.socialanalytics.service.StatsChanges;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts sample posts and 7 days of daily metric snapshots for local development.
 * Runs only in the {@code dev} profile and does nothing when the database already has posts.
 */
@Component
@Profile("dev")
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int DAYS_OF_METRICS = 7;

    private final PostRepository postRepository;
    private final SocialMetricRepository socialMetricRepository;
    private final StatsChanges statsChanges;
    private final Clock clock;

    public DataSeeder(PostRepository postRepository, SocialMetricRepository socialMetricRepository,
            StatsChanges statsChanges, Clock clock) {
        this.postRepository = postRepository;
        this.socialMetricRepository = socialMetricRepository;
        this.statsChanges = statsChanges;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (postRepository.count() > 0) {
            log.info("Skip seeding: database already has posts");
            return;
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.HOURS);
        List<SamplePost> samples = List.of(
                new SamplePost(SocialPlatform.FACEBOOK, "sample-fb-1001", "sun.asterisk",
                        "Sun* tuyển thực tập sinh Java mùa hè", 120, 15, 30, 25_000),
                new SamplePost(SocialPlatform.FACEBOOK, "sample-fb-1002", "sun.asterisk",
                        "Chia sẻ kinh nghiệm dùng Spring Boot 4", 80, 22, 12, 25_000),
                new SamplePost(SocialPlatform.TWITTER, "sample-tw-2001", "sun_asterisk",
                        "Release notes: Social Analytics Dashboard v0.1", 45, 9, 6, 8_000),
                new SamplePost(SocialPlatform.TWITTER, "sample-tw-2002", "awesome_academy",
                        "Tips: viết test với Testcontainers", 60, 18, 4, 3_500));

        List<SocialMetric> metrics = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            SamplePost sample = samples.get(i);
            Post post = new Post(sample.platform(), sample.externalId(), sample.accountName());
            post.setContent(sample.content());
            post.setUrl(sampleUrl(sample));
            post.setPublishedAt(now.minus(Duration.ofDays(DAYS_OF_METRICS)).minus(Duration.ofHours(i + 1L)));
            postRepository.save(post);

            for (int day = DAYS_OF_METRICS - 1; day >= 0; day--) {
                int elapsed = DAYS_OF_METRICS - day;
                SocialMetric metric = new SocialMetric(post, now.minus(Duration.ofDays(day)));
                metric.setLikes((long) sample.likesPerDay() * elapsed);
                metric.setShares((long) sample.sharesPerDay() * elapsed);
                metric.setComments((long) sample.commentsPerDay() * elapsed);
                metric.setFollowers(sample.followers() + 10L * elapsed);
                metrics.add(metric);
            }
        }
        socialMetricRepository.saveAll(metrics);
        // The charts read the daily statistics: compute the seeded week once committed.
        statsChanges.snapshotsChanged(now.minus(Duration.ofDays(DAYS_OF_METRICS - 1L)));
        log.info("Seeded {} posts and {} metric snapshots", samples.size(), metrics.size());
    }

    private static String sampleUrl(SamplePost sample) {
        return switch (sample.platform()) {
            case FACEBOOK -> "https://www.facebook.com/" + sample.accountName() + "/posts/" + sample.externalId();
            case TWITTER -> "https://x.com/" + sample.accountName() + "/status/" + sample.externalId();
        };
    }

    private record SamplePost(SocialPlatform platform, String externalId, String accountName, String content,
                              int likesPerDay, int sharesPerDay, int commentsPerDay, long followers) {
    }
}
