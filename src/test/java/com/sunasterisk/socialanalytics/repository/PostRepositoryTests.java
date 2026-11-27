package com.sunasterisk.socialanalytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialMetric;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.support.FixedClockConfiguration;
import com.sunasterisk.socialanalytics.util.LikePatterns;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    void sumsTheAdCostOfThePostsThatHaveOneAndTheDatabaseRefusesANegativeOne() {
        BigDecimal before = postRepository.totalAdCost();
        Post first = new Post(SocialPlatform.FACEBOOK, "post-repo-ad-1", "page");
        first.setAdCost(new BigDecimal("12.34"));
        Post second = new Post(SocialPlatform.FACEBOOK, "post-repo-ad-2", "page");
        second.setAdCost(new BigDecimal("0.66"));
        Post withoutCost = new Post(SocialPlatform.TWITTER, "post-repo-ad-3", "page");
        postRepository.saveAllAndFlush(List.of(first, second, withoutCost));

        assertThat(postRepository.totalAdCost().subtract(before)).isEqualByComparingTo("13.00");
        assertThatThrownBy(() -> jdbcTemplate.update("update posts set ad_cost = -0.01 where id = ?", first.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_posts_ad_cost");
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

    @Test
    void existsByPlatformAndExternalIdMatchesBothColumns() {
        postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, "post-repo-exists", "a"));

        assertThat(postRepository.existsByPlatformAndExternalId(SocialPlatform.FACEBOOK, "post-repo-exists")).isTrue();
        assertThat(postRepository.existsByPlatformAndExternalId(SocialPlatform.TWITTER, "post-repo-exists")).isFalse();
        assertThat(postRepository.existsByPlatformAndExternalId(SocialPlatform.FACEBOOK, "post-repo-other")).isFalse();
    }

    @Test
    void searchFiltersByPlatformAndByKeywordInContentOrAccountName() {
        String token = token();
        Post inContent = postRepository.save(post(SocialPlatform.FACEBOOK, "acc", "Giảm " + token + " hôm nay"));
        Post inAccount = postRepository.save(post(SocialPlatform.FACEBOOK, "acc-" + token, "khác"));
        Post otherPlatform = postRepository.save(post(SocialPlatform.TWITTER, "acc", token));
        postRepository.save(post(SocialPlatform.FACEBOOK, "acc", "không liên quan"));
        postRepository.flush();
        String pattern = LikePatterns.contains(token);

        assertThat(search(SocialPlatform.FACEBOOK, pattern)).extracting(Post::getId)
                .containsExactly(inAccount.getId(), inContent.getId());
        assertThat(search(null, pattern)).extracting(Post::getId)
                .containsExactly(otherPlatform.getId(), inAccount.getId(), inContent.getId());
    }

    @Test
    void searchTreatsWildcardsAndEscapeCharacterLiterally() {
        String token = token();
        Post percent = postRepository.save(post(SocialPlatform.FACEBOOK, "acc", token + " giảm 50% hôm nay"));
        Post bang = postRepository.save(post(SocialPlatform.FACEBOOK, "acc", token + " wow!"));
        postRepository.save(post(SocialPlatform.FACEBOOK, "acc", token + " giảm 50k hôm nay"));
        postRepository.flush();

        assertThat(search(null, LikePatterns.contains(token + " giảm 50%"))).extracting(Post::getId)
                .containsExactly(percent.getId());
        // Unescaped, "_" would match any character (the "%" and the "k" above).
        assertThat(search(null, LikePatterns.contains(token + " giảm 50_"))).isEmpty();
        assertThat(search(null, LikePatterns.contains(token + " wow!"))).extracting(Post::getId)
                .containsExactly(bang.getId());
    }

    @Test
    void searchPagesWithTotalCount() {
        String token = token();
        for (int i = 0; i < 3; i++) {
            postRepository.save(post(SocialPlatform.TWITTER, "acc", token + " " + i));
        }
        postRepository.flush();

        Page<Post> page = postRepository.search(null, LikePatterns.contains(token),
                PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent().getFirst().getContent()).isEqualTo(token + " 0");
    }

    @Test
    void lockingReadsReturnThePostOrEmpty() {
        Post post = postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, "post-repo-lock", "a"));
        entityManager.clear();

        assertThat(postRepository.findByIdForUpdate(post.getId())).get()
                .extracting(Post::getExternalId).isEqualTo("post-repo-lock");
        assertThat(postRepository.findByIdForShare(post.getId())).isPresent();
        assertThat(postRepository.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
        assertThat(postRepository.findByIdForShare(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void deleteByIdReturningCountReportsWhetherARowWasDeleted() {
        Post post = postRepository.saveAndFlush(new Post(SocialPlatform.FACEBOOK, "post-repo-delete-count", "a"));
        socialMetricRepository.saveAndFlush(new SocialMetric(post, Instant.parse("2026-02-28T00:00:00Z")));
        entityManager.clear();

        assertThat(postRepository.deleteByIdReturningCount(post.getId())).isEqualTo(1);
        assertThat(postRepository.deleteByIdReturningCount(post.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from social_metrics where post_id = ?", Integer.class, post.getId())).isZero();
    }

    private List<Post> search(SocialPlatform platform, String pattern) {
        return postRepository.search(platform, pattern, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")))
                .getContent();
    }

    private static Post post(SocialPlatform platform, String accountName, String content) {
        Post post = new Post(platform, "post-repo-" + UUID.randomUUID(), accountName);
        post.setContent(content);
        return post;
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
