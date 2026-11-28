package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import com.sunasterisk.socialanalytics.entity.UserRole;
import com.sunasterisk.socialanalytics.repository.UserRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code users} in step with the logins: one row per {@code (provider, provider_user_id)}, updated with the
 * provider's latest profile, and the role set again at every login from the admin list
 * ({@code docs/cau-hoi-lam-ro.md} Q1).
 */
@Service
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    static final int DISPLAY_NAME_MAX_LENGTH = 100;
    static final int EMAIL_MAX_LENGTH = 255;
    static final int AVATAR_URL_MAX_LENGTH = 1000;

    private final UserRepository userRepository;
    private final TransactionTemplate transaction;

    public UserAccountService(UserRepository userRepository, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates or updates the user of a login and returns it. Two first logins of the same account at once both try
     * to insert; the UNIQUE constraint stops the second, which then updates the row the first one created.
     */
    public User sync(SocialProfile profile, UserRole role) {
        try {
            return save(profile, role);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent first login of {}:{}, updating instead", profile.provider(),
                    profile.providerUserId());
            return save(profile, role);
        }
    }

    private User save(SocialProfile profile, UserRole role) {
        User saved = transaction.execute(status -> {
            User user = userRepository.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                    .orElseGet(() -> new User(profile.provider(), profile.providerUserId(),
                            cut(profile.displayName(), DISPLAY_NAME_MAX_LENGTH)));
            user.setDisplayName(cut(profile.displayName(), DISPLAY_NAME_MAX_LENGTH));
            user.setEmail(cut(profile.email(), EMAIL_MAX_LENGTH));
            user.setAvatarUrl(cut(profile.avatarUrl(), AVATAR_URL_MAX_LENGTH));
            user.setRole(role);
            return userRepository.saveAndFlush(user);
        });
        if (saved == null) {
            throw new IllegalStateException("User not saved");
        }
        return saved;
    }

    private static @Nullable String cut(@Nullable String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** What a provider tells about the account that logged in. */
    public record SocialProfile(SocialPlatform provider, String providerUserId, String displayName,
            @Nullable String email, @Nullable String avatarUrl) {
    }
}
