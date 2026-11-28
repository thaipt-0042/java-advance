package com.sunasterisk.socialanalytics.security;

import static com.sunasterisk.socialanalytics.support.TestEntities.persisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import com.sunasterisk.socialanalytics.entity.UserRole;
import com.sunasterisk.socialanalytics.service.UserAccountService;
import com.sunasterisk.socialanalytics.service.UserAccountService.SocialProfile;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

class SocialOAuth2UserServiceTests {

    private static final Instant NOW = Instant.parse("2026-03-01T03:00:00Z");
    private static final SocialLoginProperties PROPERTIES = new SocialLoginProperties(
            new SocialLoginProperties.Provider("fb-id", "fb-secret"),
            new SocialLoginProperties.Provider("x-id", "x-secret"), "v27.0", TokenCipherTests.KEY,
            "TWITTER:44196397");

    @SuppressWarnings("unchecked")
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final SocialOAuth2UserService service = new SocialOAuth2UserService(delegate, accounts,
            PROPERTIES.admins());

    @Test
    void anAdminListedXAccountBecomesAdminWithItsProfile() throws Exception {
        provider(SocialPlatform.TWITTER, Map.of("id", "44196397", "name", "Sun*", "username", "sun_asterisk",
                "profile_image_url", "https://pbs.twimg.com/a.jpg"));
        when(accounts.sync(any(), eq(UserRole.ADMIN))).thenAnswer(inv -> user(inv.getArgument(0), UserRole.ADMIN));

        OAuth2User principal = service.loadUser(request(SocialPlatform.TWITTER));

        verify(accounts).sync(new SocialProfile(SocialPlatform.TWITTER, "44196397", "Sun*", null,
                "https://pbs.twimg.com/a.jpg"), UserRole.ADMIN);
        assertThat(principal.getName()).isEqualTo("44196397");
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(((SocialUser) principal).userId()).isEqualTo(5L);
        assertThat(((SocialUser) principal).displayName()).isEqualTo("Sun*");
        // Lives in the session stored in MySQL: must serialize.
        try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
            out.writeObject(principal);
        }
    }

    @Test
    void aFacebookAccountNotListedIsAUserWithEmailAndPicture() {
        provider(SocialPlatform.FACEBOOK, Map.of("id", "1001", "name", "Thái", "email", "thai@example.com",
                "picture", Map.of("data", Map.of("url", "https://graph.facebook.com/pic.jpg"))));
        when(accounts.sync(any(), eq(UserRole.USER))).thenAnswer(inv -> user(inv.getArgument(0), UserRole.USER));

        OAuth2User principal = service.loadUser(request(SocialPlatform.FACEBOOK));

        verify(accounts).sync(new SocialProfile(SocialPlatform.FACEBOOK, "1001", "Thái", "thai@example.com",
                "https://graph.facebook.com/pic.jpg"), UserRole.USER);
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    @Test
    void theSameIdOnTheOtherPlatformIsNotAnAdmin() {
        provider(SocialPlatform.FACEBOOK, Map.of("id", "44196397"));
        when(accounts.sync(any(), eq(UserRole.USER))).thenAnswer(inv -> user(inv.getArgument(0), UserRole.USER));

        service.loadUser(request(SocialPlatform.FACEBOOK));

        // No name either: the id is shown.
        verify(accounts).sync(new SocialProfile(SocialPlatform.FACEBOOK, "44196397", "44196397", null, null),
                UserRole.USER);
    }

    @Test
    void anAnswerWithoutIdFailsTheLogin() {
        provider(SocialPlatform.TWITTER, Map.of("username", "nobody", "x", "y"));

        assertThatThrownBy(() -> service.loadUser(request(SocialPlatform.TWITTER)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("did not return the account id");
    }

    private void provider(SocialPlatform platform, Map<String, Object> attributes) {
        String key = attributes.containsKey("id") ? "id" : "username";
        when(delegate.loadUser(any())).thenReturn(new DefaultOAuth2User(List.of(), attributes, key));
    }

    private static OAuth2UserRequest request(SocialPlatform platform) {
        ClientRegistration registration = SocialClientRegistrations.of(PROPERTIES).stream()
                .filter(candidate -> SocialClientRegistrations.platformOf(candidate) == platform)
                .findFirst().orElseThrow();
        return new OAuth2UserRequest(registration, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "token", NOW, NOW.plusSeconds(3600)));
    }

    private static User user(SocialProfile profile, UserRole role) {
        User user = new User(profile.provider(), profile.providerUserId(), profile.displayName());
        user.setRole(role);
        return persisted(user, 5L, NOW, NOW);
    }
}
