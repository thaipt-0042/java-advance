package com.sunasterisk.socialanalytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialMetric;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.support.FixedClockConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class PostRepositoryTests extends JpaRepositoryTestBase {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesPostWithAuditTimestampsAndPlatformStoredByName() {
        Post post = new Post(SocialPlatform.FACEBOOK, "post-repo-1", "sun.asterisk");
        post.setContent("Xin chào");
        post.setPublishedAt(Instant.parse("2026-02-28T17:30:00Z"));
        postRepository.saveAndFlush(post);
        entityManager.clear();

        Post found = postRepository.findByPlatformAndExternalId(SocialPlatform.FACEBOOK, "post-repo-1").orElseThrow();
        assertThat(found.getAccountName()).isEqualTo("sun.asterisk");
        assertThat(found.getContent()).isEqualTo("Xin chào");
        assertThat(found.getPublishedAt()).isEqualTo(Instant.parse("2026-02-28T17:30:00Z"));
        assertThat(found.getCreatedAt()).isEqualTo(FixedClockConfiguration.NOW);
        assertThat(found.getUpdatedAt()).isEqualTo(FixedClockConfiguration.NOW);
        assertThat(jdbcTemplate.queryForObject("select platform from posts where id = ?", String.class, found.getId()))
                .isEqualTo("FACEBOOK");
    }

    @Test
    void storesTimestampsInUtc() {
        Post post = new Post(SocialPlatform.TWITTER, "post-repo-utc", "sun_asterisk");
        post.setPublishedAt(Instant.parse("2026-02-28T17:30:00Z"));
        postRepository.saveAndFlush(post);

        String stored = jdbcTemplate.queryForObject(
                "select date_format(published_at, '%Y-%m-%d %H:%i:%s') from posts where id = ?",
                String.class, post.getId());
        assertThat(stored).isEqualTo("2026-02-28 17:30:00");
    }

    @Test
    void rejectsDuplicateExternalIdOnSamePlatform() {
        postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, "post-repo-dup", "a"));

        assertThatThrownBy(() -> postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, "post-repo-dup", "b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameExternalIdOnDifferentPlatforms() {
        postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, "post-repo-cross", "a"));
        postRepository.saveAndFlush(new Post(SocialPlatform.TWITTER, "post-repo-cross", "a"));

        assertThat(postRepository.findByPlatformAndExternalId(SocialPlatform.TWITTER, "post-repo-cross")).isPresent();
    }

    @Test
    void deletingPostDeletesItsMetrics() {
        Post post = postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, "post-repo-delete", "a"));
        socialMetricRepository.saveAndFlush(new SocialMetric(post, Instant.parse("2026-02-28T00:00:00Z")));
        socialMetricRepository.saveAndFlush(new SocialMetric(post, Instant.parse("2026-03-01T00:00:00Z")));
        entityManager.clear();

        postRepository.deleteById(post.getId());
        postRepository.flush();

        Integer remaining = jdbcTemplate.queryForObject(
                "select count(*) from social_metrics where post_id = ?", Integer.class, post.getId());
        assertThat(remaining).isZero();
    }
}
