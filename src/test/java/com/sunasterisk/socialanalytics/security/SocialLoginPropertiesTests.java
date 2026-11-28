package com.sunasterisk.socialanalytics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.security.SocialLoginProperties.AdminAccount;
import com.sunasterisk.socialanalytics.security.SocialLoginProperties.Provider;
import org.junit.jupiter.api.Test;

class SocialLoginPropertiesTests {

    private static final Provider OFF = new Provider(null, null);
    private static final Provider ON = new Provider("id", "secret");

    @Test
    void noProviderNeedsNoKey() {
        SocialLoginProperties properties = new SocialLoginProperties(OFF, new Provider("", ""), "v27.0", null, null);

        assertThat(properties.enabledPlatforms()).isEmpty();
        assertThat(properties.admins()).isEmpty();
    }

    @Test
    void aProviderNeedsBothValuesAndTheKey() {
        assertThatThrownBy(() -> new SocialLoginProperties(new Provider("id", null), OFF, "v27.0", null, null))
                .hasMessage("app.oauth2.facebook: set both client-id and client-secret, or neither");
        assertThatThrownBy(() -> new SocialLoginProperties(OFF, ON, "v27.0", " ", null))
                .hasMessageStartingWith("OAuth2 login is configured: set app.oauth2.token-encryption-key");
        assertThatThrownBy(() -> new SocialLoginProperties(OFF, ON, "v27.0", "c2hvcnQ=", null))
                .hasMessage("Token encryption key must be 32 bytes (got 5)");

        SocialLoginProperties properties = new SocialLoginProperties(ON, ON, "v27.0", TokenCipherTests.KEY, null);
        assertThat(properties.enabledPlatforms()).containsExactly(SocialPlatform.FACEBOOK, SocialPlatform.TWITTER);
    }

    @Test
    void adminAccountsArePlatformColonId() {
        SocialLoginProperties properties = new SocialLoginProperties(OFF, OFF, "v27.0", null,
                " FACEBOOK:123 , twitter:44196397,, ");

        assertThat(properties.admins()).containsExactlyInAnyOrder(new AdminAccount(SocialPlatform.FACEBOOK, "123"),
                new AdminAccount(SocialPlatform.TWITTER, "44196397"));
        assertThatThrownBy(() -> new SocialLoginProperties(OFF, OFF, "v27.0", null, "FACEBOOK123"))
                .hasMessageContaining("\"FACEBOOK123\" is not PLATFORM:id");
        assertThatThrownBy(() -> new SocialLoginProperties(OFF, OFF, "v27.0", null, "INSTAGRAM:1"))
                .hasMessageContaining("\"INSTAGRAM:1\"");
        assertThatThrownBy(() -> new SocialLoginProperties(OFF, OFF, "v27.0", null, "TWITTER: "))
                .hasMessageContaining("is not PLATFORM:id");
    }
}
