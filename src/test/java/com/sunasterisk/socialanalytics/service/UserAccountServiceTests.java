package com.sunasterisk.socialanalytics.service;

import static com.sunasterisk.socialanalytics.support.TestEntities.persisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import com.sunasterisk.socialanalytics.entity.UserRole;
import com.sunasterisk.socialanalytics.repository.UserRepository;
import com.sunasterisk.socialanalytics.service.UserAccountService.SocialProfile;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

class UserAccountServiceTests {

    private static final Instant NOW = Instant.parse("2026-03-01T03:00:00Z");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserAccountService service = new UserAccountService(userRepository,
            mock(PlatformTransactionManager.class));

    @Test
    void firstLoginCreatesTheUserWithTheRole() {
        when(userRepository.findByProviderAndProviderUserId(SocialPlatform.TWITTER, "44")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> persisted(inv.<User>getArgument(0), 7L, NOW, NOW));

        User user = service.sync(new SocialProfile(SocialPlatform.TWITTER, "44", "Sun*", null,
                "https://pbs.twimg.com/a.jpg"), UserRole.ADMIN);

        assertThat(user).extracting(User::getId, User::getProvider, User::getProviderUserId, User::getDisplayName,
                User::getEmail, User::getAvatarUrl, User::getRole).containsExactly(7L, SocialPlatform.TWITTER, "44",
                "Sun*", null, "https://pbs.twimg.com/a.jpg", UserRole.ADMIN);
    }

    @Test
    void laterLoginsUpdateTheProfileAndTheRoleAndCutLongValues() {
        User existing = persisted(new User(SocialPlatform.FACEBOOK, "1", "Old name"), 3L, NOW, NOW);
        existing.setRole(UserRole.ADMIN);
        when(userRepository.findByProviderAndProviderUserId(SocialPlatform.FACEBOOK, "1"))
                .thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(existing)).thenReturn(existing);

        service.sync(new SocialProfile(SocialPlatform.FACEBOOK, "1", "n".repeat(150), "a@example.com", null),
                UserRole.USER);

        assertThat(existing.getDisplayName()).hasSize(UserAccountService.DISPLAY_NAME_MAX_LENGTH);
        assertThat(existing.getEmail()).isEqualTo("a@example.com");
        // Removed from the admin list: back to USER at the next login.
        assertThat(existing.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void twoFirstLoginsAtOnceEndWithAnUpdateOfTheRowTheOtherCreated() {
        User createdMeanwhile = persisted(new User(SocialPlatform.TWITTER, "44", "Sun*"), 9L, NOW, NOW);
        when(userRepository.findByProviderAndProviderUserId(SocialPlatform.TWITTER, "44"))
                .thenReturn(Optional.empty(), Optional.of(createdMeanwhile));
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry 'TWITTER-44'"))
                .thenReturn(createdMeanwhile);

        User user = service.sync(new SocialProfile(SocialPlatform.TWITTER, "44", "Sun*", null, null), UserRole.USER);

        assertThat(user.getId()).isEqualTo(9L);
    }
}
