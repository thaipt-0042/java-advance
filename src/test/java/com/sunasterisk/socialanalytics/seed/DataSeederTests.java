package com.sunasterisk.socialanalytics.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.SocialMetricRepository;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
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
    void seedsEmptyDatabaseOnceWithAWeekOfMetricsPerPost() {
        // The database is shared with other test classes: start from empty tables instead of relying on run order.
        jdbcTemplate.update("delete from social_metrics");
        jdbcTemplate.update("delete from posts");

        dataSeeder.run(new DefaultApplicationArguments());

        assertThat(postRepository.count()).isEqualTo(4);
        assertThat(socialMetricRepository.count()).isEqualTo(28);
        Post post = postRepository.findByPlatformAndExternalId(SocialPlatform.FACEBOOK, "sample-fb-1001").orElseThrow();
        assertThat(socialMetricRepository.findByPostIdOrderByCapturedAtAsc(post.getId())).hasSize(7);

        dataSeeder.run(new DefaultApplicationArguments());

        assertThat(postRepository.count()).isEqualTo(4);
        assertThat(socialMetricRepository.count()).isEqualTo(28);
    }
}
