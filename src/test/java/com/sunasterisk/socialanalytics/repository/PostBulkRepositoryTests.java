package com.sunasterisk.socialanalytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialMetric;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository.NewSnapshot;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository.PostId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(PostBulkRepository.class)
class PostBulkRepositoryTests extends JpaRepositoryTestBase {

    private static final Instant NOW = Instant.parse("2026-03-05T03:00:00.123456Z");

    @Autowired
    private PostBulkRepository bulkRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository metricRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insertedPostsReadBackThroughJpaWithTheSameInstants() {
        String t = UUID.randomUUID().toString().substring(0, 8);
        Post full = new Post(SocialPlatform.FACEBOOK, t + "-1", "page");
        full.setUrl("https://example.com/1");
        full.setContent("Xin chào");
        full.setPublishedAt(Instant.parse("2026-02-28T17:30:00.000001Z"));

        bulkRepository.insertPosts(List.of(full, new Post(SocialPlatform.FACEBOOK, t + "-2", "page")), NOW);

        Post found = postRepository.findByPlatformAndExternalId(SocialPlatform.FACEBOOK, t + "-1").orElseThrow();
        assertThat(found).extracting(Post::getAccountName, Post::getUrl, Post::getContent, Post::getPublishedAt,
                Post::getCreatedAt, Post::getUpdatedAt).containsExactly("page", "https://example.com/1", "Xin chào",
                Instant.parse("2026-02-28T17:30:00.000001Z"), NOW, NOW);
        // Stored as UTC wall-clock time, like the columns Hibernate writes.
        assertThat(jdbcTemplate.queryForObject("select cast(published_at as char) from posts where id = ?",
                String.class, found.getId())).isEqualTo("2026-02-28 17:30:00.000001");
        assertThat(postRepository.findByPlatformAndExternalId(SocialPlatform.FACEBOOK, t + "-2")).hasValueSatisfying(
                post -> assertThat(post).extracting(Post::getUrl, Post::getContent, Post::getPublishedAt)
                        .containsOnlyNulls());
    }

    @Test
    void findsIdsWithTheColumnCollation() {
        String t = UUID.randomUUID().toString().substring(0, 8);
        bulkRepository.insertPosts(List.of(new Post(SocialPlatform.TWITTER, "Ab-" + t, "page")), NOW);
        long id = postRepository.findByPlatformAndExternalId(SocialPlatform.TWITTER, "Ab-" + t).orElseThrow().getId();

        assertThat(bulkRepository.findIds(SocialPlatform.TWITTER, List.of("ab-" + t, "missing-" + t)))
                .containsExactly(new PostId(id, SocialPlatform.TWITTER, "Ab-" + t));
        assertThat(bulkRepository.findIds(SocialPlatform.FACEBOOK, List.of("Ab-" + t))).isEmpty();
    }

    @Test
    void insertsSnapshotsSharingCapturedAt() {
        Post post = postRepository.saveAndFlush(
                new Post(SocialPlatform.FACEBOOK, "bulk-" + UUID.randomUUID(), "page"));
        Instant capturedAt = Instant.parse("2026-03-05T03:00:00.654321Z");

        bulkRepository.insertSnapshots(List.of(new NewSnapshot(post.getId(), 10, 2, 3, 100)), capturedAt, NOW);
        entityManager.clear();

        assertThat(metricRepository.findByPostIdOrderByCapturedAtAsc(post.getId())).singleElement()
                .extracting(SocialMetric::getLikes, SocialMetric::getShares, SocialMetric::getComments,
                        SocialMetric::getFollowers, SocialMetric::getCapturedAt, SocialMetric::getCreatedAt)
                .containsExactly(10L, 2L, 3L, 100L, capturedAt, NOW);
    }

    @Test
    void duplicatePostIsRejectedByTheUniqueConstraint() {
        String externalId = "dup-" + UUID.randomUUID();
        postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, externalId, "page"));

        assertThatThrownBy(() -> bulkRepository.insertPosts(
                List.of(new Post(SocialPlatform.FACEBOOK, externalId.toUpperCase(), "page")), NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
