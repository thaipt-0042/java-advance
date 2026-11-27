package com.sunasterisk.socialanalytics.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

/** The {@code users} row of the logged-in person, when there is one (Facebook / X logins; not the demo accounts). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static @Nullable Long userId(@Nullable Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof SocialUser user
                ? user.userId() : null;
    }

    /** The name to show: the provider's display name, or the login name of a demo account. */
    public static String displayName(Authentication authentication) {
        return authentication.getPrincipal() instanceof SocialUser user ? user.displayName() : authentication.getName();
    }
}
