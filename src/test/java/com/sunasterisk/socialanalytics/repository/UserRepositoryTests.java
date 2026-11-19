package com.sunasterisk.socialanalytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import com.sunasterisk.socialanalytics.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class UserRepositoryTests extends JpaRepositoryTestBase {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserByProviderAccountWithDefaultRole() {
        userRepository.saveAndFlush(new User(SocialPlatform.FACEBOOK, "user-repo-fb-1", "Thái"));

        User found = userRepository.findByProviderAndProviderUserId(SocialPlatform.FACEBOOK, "user-repo-fb-1")
                .orElseThrow();
        assertThat(found.getDisplayName()).isEqualTo("Thái");
        assertThat(found.getRole()).isEqualTo(UserRole.USER);
        assertThat(userRepository.findByProviderAndProviderUserId(SocialPlatform.TWITTER, "user-repo-fb-1")).isEmpty();
    }

    @Test
    void rejectsSameProviderAccountTwice() {
        userRepository.saveAndFlush(new User(SocialPlatform.TWITTER, "user-repo-tw-dup", "A"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User(SocialPlatform.TWITTER, "user-repo-tw-dup", "B")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameEmailOnDifferentProviders() {
        User facebook = new User(SocialPlatform.FACEBOOK, "user-repo-email-fb", "A");
        facebook.setEmail("same@example.com");
        User twitter = new User(SocialPlatform.TWITTER, "user-repo-email-tw", "A");
        twitter.setEmail("same@example.com");

        userRepository.saveAndFlush(facebook);
        userRepository.saveAndFlush(twitter);

        assertThat(twitter.getId()).isNotNull();
    }
}
