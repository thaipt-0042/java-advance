package com.sunasterisk.socialanalytics.security;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Login with Facebook / X ({@code app.oauth2.*}). A provider is on when both its client id and secret are set;
 * none set means no OAuth2 login (dev and tests use the demo login). Checked at startup:
 *
 * <ul>
 *   <li>a provider with only one of id / secret;</li>
 *   <li>a provider on without {@code tokenEncryptionKey} (Base64 of 32 bytes): tokens are only stored encrypted
 *       ({@code docs/cau-hoi-lam-ro.md} Q8);</li>
 *   <li>{@code adminAccounts} not in the form {@code FACEBOOK:<id>,TWITTER:<id>} (Q1).</li>
 * </ul>
 *
 * @param facebookGraphVersion version of the Facebook Graph API used in the endpoints (e.g. {@code v27.0})
 */
@ConfigurationProperties("app.oauth2")
public record SocialLoginProperties(
        @DefaultValue Provider facebook,
        @DefaultValue Provider twitter,
        @DefaultValue("v27.0") String facebookGraphVersion,
        @Nullable String tokenEncryptionKey,
        @Nullable String adminAccounts) {

    public SocialLoginProperties {
        facebook.check("facebook");
        twitter.check("twitter");
        if (!enabledPlatforms(facebook, twitter).isEmpty()) {
            if (!StringUtils.hasText(tokenEncryptionKey)) {
                throw new IllegalArgumentException("OAuth2 login is configured: set app.oauth2.token-encryption-key "
                        + "(APP_TOKEN_ENCRYPTION_KEY, Base64 of 32 bytes: openssl rand -base64 32)");
            }
            TokenCipher.fromBase64Key(tokenEncryptionKey);
        }
        parseAdmins(adminAccounts);
    }

    /** Providers whose id and secret are both set, in a fixed order. */
    public List<SocialPlatform> enabledPlatforms() {
        return enabledPlatforms(facebook, twitter);
    }

    public Provider provider(SocialPlatform platform) {
        return platform == SocialPlatform.FACEBOOK ? facebook : twitter;
    }

    /** The admin accounts as {@code (platform, provider user id)}. */
    public Set<AdminAccount> admins() {
        return parseAdmins(adminAccounts);
    }

    private static List<SocialPlatform> enabledPlatforms(Provider facebook, Provider twitter) {
        List<SocialPlatform> enabled = new ArrayList<>();
        if (facebook.enabled()) {
            enabled.add(SocialPlatform.FACEBOOK);
        }
        if (twitter.enabled()) {
            enabled.add(SocialPlatform.TWITTER);
        }
        return enabled;
    }

    private static Set<AdminAccount> parseAdmins(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        Map<String, SocialPlatform> platforms = Arrays.stream(SocialPlatform.values())
                .collect(Collectors.toMap(SocialPlatform::name, platform -> platform));
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .map(entry -> {
                    String[] parts = entry.split(":", 2);
                    SocialPlatform platform = parts.length == 2
                            ? platforms.get(parts[0].strip().toUpperCase(Locale.ROOT)) : null;
                    if (platform == null || !StringUtils.hasText(parts[1])) {
                        throw new IllegalArgumentException(("app.oauth2.admin-accounts (APP_ADMIN_ACCOUNTS): \"%s\" "
                                + "is not PLATFORM:id, e.g. FACEBOOK:1234567890,TWITTER:44196397").formatted(entry));
                    }
                    return new AdminAccount(platform, parts[1].strip());
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Client credentials of one provider; both empty means off. */
    public record Provider(@Nullable String clientId, @Nullable String clientSecret) {

        public boolean enabled() {
            return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
        }

        void check(String name) {
            if (StringUtils.hasText(clientId) != StringUtils.hasText(clientSecret)) {
                throw new IllegalArgumentException(
                        "app.oauth2.%s: set both client-id and client-secret, or neither".formatted(name));
            }
        }
    }

    public record AdminAccount(SocialPlatform platform, String providerUserId) {
    }
}
