package com.sunasterisk.socialanalytics.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.SocialMetricRepository;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"dev", "test"})
class DataSeederTests {

    @Autowired
    private DataSeeder dataSeeder;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedsEmptyDatabaseOnceWithAWeekOfMetricsPerPost() throws InterruptedException {
        // The database is shared with other test classes: start from empty tables instead of relying on run order.
        jdbcTemplate.update("delete from social_metrics");
        jdbcTemplate.update("delete from posts");

        dataSeeder.run(new DefaultApplicationArguments());

        assertThat(postRepository.count()).isEqualTo(4);
        assertThat(socialMetricRepository.count()).isEqualTo(28);
        Post post = postRepository.findByPlatformAndExternalId(SocialPlatform.FACEBOOK, "sample-fb-1001").orElseThrow();
        assertThat(socialMetricRepository.findByPostIdOrderByCapturedAtAsc(post.getId())).hasSize(7);
        // The seeded week reaches the daily statistics through the queue.
        LocalDate firstDay = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(6);
        awaitStatsRow(firstDay);

        dataSeeder.run(new DefaultApplicationArguments());

        assertThat(postRepository.count()).isEqualTo(4);
        assertThat(socialMetricRepository.count()).isEqualTo(28);
    }

    private void awaitStatsRow(LocalDate day) throws InterruptedException {
        long start = System.nanoTime();
        while (jdbcTemplate.queryForObject("select count(*) from daily_platform_stats where stat_date = ?",
                Long.class, day) < 2) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("statistics of " + day)
                    .isLessThan(Duration.ofSeconds(20));
            Thread.sleep(100);
        }
    }
}
