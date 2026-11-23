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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class SocialMetricRepositoryTests extends JpaRepositoryTestBase {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    private static final PageRequest LATEST_FIRST =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "capturedAt", "id"));

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

    @Test
    void searchFiltersByPostAndHalfOpenTimeRangeLatestFirst() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-03-02T00:00:00Z");
        Post otherPost = postRepository.saveAndFlush(new Post(SocialPlatform.TWITTER, "metric-repo-other", "x"));
        socialMetricRepository.saveAllAndFlush(List.of(
                metric(from.minusNanos(1_000), 1),
                metric(from, 2),
                metric(to.minusNanos(1_000), 3),
                metric(to, 4),
                new SocialMetric(otherPost, from.plusSeconds(60))));

        Page<SocialMetric> page = socialMetricRepository.search(post.getId(), from, to, LATEST_FIRST);

        assertThat(page.getContent()).extracting(SocialMetric::getLikes).containsExactly(3L, 2L);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void searchWithOnlyPostFilterReturnsAllSnapshotsOfThatPost() {
        socialMetricRepository.saveAllAndFlush(List.of(
                metric(Instant.parse("2025-01-01T00:00:00Z"), 1),
                metric(Instant.parse("2027-01-01T00:00:00Z"), 2)));

        assertThat(socialMetricRepository.search(post.getId(), null, null, LATEST_FIRST).getContent())
                .extracting(SocialMetric::getLikes).containsExactly(2L, 1L);
    }

    @Test
    void duplicateTimeCheckOnUpdateIgnoresTheSnapshotItself() {
        Instant capturedAt = Instant.parse("2026-03-01T00:00:00Z");
        SocialMetric first = socialMetricRepository.saveAndFlush(metric(capturedAt, 1));
        SocialMetric second = socialMetricRepository.saveAndFlush(metric(capturedAt.plusSeconds(60), 2));

        assertThat(socialMetricRepository.existsByPostIdAndCapturedAt(post.getId(), capturedAt)).isTrue();
        assertThat(socialMetricRepository.existsByPostIdAndCapturedAtAndIdNot(post.getId(), capturedAt, first.getId()))
                .isFalse();
        assertThat(socialMetricRepository.existsByPostIdAndCapturedAtAndIdNot(post.getId(), capturedAt, second.getId()))
                .isTrue();
    }

    @Test
    void lockingReadAndCountedDeleteBehaveLikeFindAndDelete() {
        SocialMetric metric = socialMetricRepository.saveAndFlush(metric(Instant.parse("2026-03-01T00:00:00Z"), 7));

        assertThat(socialMetricRepository.findByIdForUpdate(metric.getId())).get()
                .extracting(SocialMetric::getLikes).isEqualTo(7L);
        assertThat(socialMetricRepository.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
        assertThat(socialMetricRepository.deleteByIdReturningCount(metric.getId())).isEqualTo(1);
        assertThat(socialMetricRepository.deleteByIdReturningCount(metric.getId())).isZero();
    }

    private SocialMetric metric(Instant capturedAt, long likes) {
        SocialMetric metric = new SocialMetric(post, capturedAt);
        metric.setLikes(likes);
        return metric;
    }
}
