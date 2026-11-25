package com.sunasterisk.socialanalytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.Post;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Enum columns are read with {@code Enum.valueOf}, which is case sensitive. The CHECK constraints must reject every
 * value Hibernate can't read, including other cases written by SQL outside the app, so the columns use a binary
 * collation.
 */
class EnumColumnConstraintTests extends JpaRepositoryTestBase {

    private static final String NOW = "2026-03-01 00:00:00";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostRepository postRepository;

    @ParameterizedTest
    @ValueSource(strings = {"facebook", "Twitter"})
    void postPlatformRejectsOtherCase(String platform) {
        assertThatThrownBy(() -> insertPost(platform, "case-" + platform))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint 'ck_posts_platform' is violated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"facebook", "Twitter"})
    void userProviderRejectsOtherCase(String provider) {
        assertThatThrownBy(() -> insertUser(provider, "USER"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint 'ck_users_provider' is violated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "User"})
    void userRoleRejectsOtherCase(String role) {
        assertThatThrownBy(() -> insertUser("FACEBOOK", role))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint 'ck_users_role' is violated");
    }

    @Test
    void exactEnumNamesAreAcceptedAndReadBack() {
        insertPost("FACEBOOK", "case-exact");
        insertUser("TWITTER", "ADMIN");

        List<Post> posts = postRepository.findAll();
        assertThat(posts).extracting(Post::getExternalId).contains("case-exact");
    }

    private void insertPost(String platform, String externalId) {
        jdbcTemplate.update("""
                insert into posts (platform, external_id, account_name, created_at, updated_at)
                values (?, ?, 'acc', ?, ?)""", platform, externalId, NOW, NOW);
    }

    private void insertUser(String provider, String role) {
        jdbcTemplate.update("""
                insert into users (provider, provider_user_id, display_name, role, created_at, updated_at)
                values (?, ?, 'name', ?, ?, ?)""", provider, "case-" + provider + "-" + role, role, NOW, NOW);
    }
}
