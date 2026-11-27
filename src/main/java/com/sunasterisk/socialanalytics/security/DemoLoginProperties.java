package com.sunasterisk.socialanalytics.security;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Demo form login ({@code docs/cau-hoi-lam-ro.md} Q2): two accounts, {@value #ADMIN_USERNAME} ({@code ADMIN}) and
 * {@value #USER_USERNAME} ({@code USER}), whose passwords come from the environment. On in the dev and test
 * profiles only; elsewhere nobody can log in until OAuth2 Login (#9).
 *
 * <p>Enabled without a password (or with one shorter than {@value #MIN_PASSWORD_LENGTH} characters), the app
 * refuses to start rather than run with a guessable account.
 */
@ConfigurationProperties("app.demo-login")
public record DemoLoginProperties(boolean enabled, @Nullable String adminPassword, @Nullable String userPassword) {

    public static final String ADMIN_USERNAME = "demo-admin";
    public static final String USER_USERNAME = "demo-user";
    static final int MIN_PASSWORD_LENGTH = 8;

    public DemoLoginProperties {
        if (enabled) {
            requireStrong(adminPassword, "app.demo-login.admin-password (APP_DEMO_ADMIN_PASSWORD)");
            requireStrong(userPassword, "app.demo-login.user-password (APP_DEMO_USER_PASSWORD)");
        }
    }

    private static void requireStrong(@Nullable String password, String property) {
        if (!StringUtils.hasText(password) || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Demo login is enabled: set %s, at least %d characters"
                    .formatted(property, MIN_PASSWORD_LENGTH));
        }
    }
}
