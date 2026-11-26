package com.sunasterisk.socialanalytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialMetric;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class SocialMetricRepositoryTests extends JpaRepositoryTestBase {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    private Post post;

    @BeforeEach
    void createPost() {
        post = postRepository.saveAndFlush(new Post(SocialPlatform.TWITTER, "metric-repo-post", "sun_asterisk"));
    }

    @Test
    void listsSnapshotsOfAPostInCaptureOrder() {
        SocialMetric later = metric(Instant.parse("2026-03-01T01:00:00Z"), 20);
        SocialMetric earlier = metric(Instant.parse("2026-03-01T00:00:00Z"), 10);
        socialMetricRepository.saveAllAndFlush(List.of(later, earlier));

        assertThat(socialMetricRepository.findByPostIdOrderByCapturedAtAsc(post.getId()))
                .extracting(SocialMetric::getLikes)
                .containsExactly(10L, 20L);
    }

    @Test
    void rejectsTwoSnapshotsOfSamePostAtSameTime() {
        Instant capturedAt = Instant.parse("2026-03-01T00:00:00Z");
        socialMetricRepository.saveAndFlush(metric(capturedAt, 1));

        assertThatThrownBy(() -> socialMetricRepository.saveAndFlush(metric(capturedAt, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeCounters() {
        SocialMetric metric = metric(Instant.parse("2026-03-01T00:00:00Z"), 0);
        metric.setFollowers(-1);

        assertThatThrownBy(() -> socialMetricRepository.saveAndFlush(metric))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_social_metrics_non_negative");
    }

    private SocialMetric metric(Instant capturedAt, long likes) {
        SocialMetric metric = new SocialMetric(post, capturedAt);
        metric.setLikes(likes);
        return metric;
    }
}
