package com.sunasterisk.socialanalytics.security;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The Facebook and X client registrations, built from {@link SocialLoginProperties} (only the providers whose id and
 * secret are set). Registration ids are the platform names in lower case: {@code /oauth2/authorization/facebook},
 * {@code /login/oauth2/code/twitter}, and {@code oauth2_authorized_client.client_registration_id}.
 *
 * <ul>
 *   <li>Facebook: Graph API endpoints of the configured version; email and picture requested in the user info.</li>
 *   <li>X (API v2): {@code https://x.com/i/oauth2/authorize}, token at {@code https://api.x.com/2/oauth2/token} with
 *       HTTP Basic client authentication, user at {@code https://api.x.com/2/users/me} (scopes {@code users.read},
 *       {@code tweet.read}), with PKCE (S256).</li>
 * </ul>
 */
final class SocialClientRegistrations {

    static final String CALLBACK = "{baseUrl}/login/oauth2/code/{registrationId}";

    private SocialClientRegistrations() {
    }

    static List<ClientRegistration> of(SocialLoginProperties properties) {
        return properties.enabledPlatforms().stream()
                .map(platform -> switch (platform) {
                    case FACEBOOK -> facebook(properties.facebook(), properties.facebookGraphVersion());
                    case TWITTER -> twitter(properties.twitter());
                })
                .toList();
    }

    static String registrationId(SocialPlatform platform) {
        return platform.name().toLowerCase(Locale.ROOT);
    }

    static SocialPlatform platformOf(ClientRegistration registration) {
        return SocialPlatform.valueOf(registration.getRegistrationId().toUpperCase(Locale.ROOT));
    }

    /** Spring Security's user info client, with X's {@code data} wrapper removed. */
    static DefaultOAuth2UserService userInfoClient() {
        DefaultOAuth2UserService service = new DefaultOAuth2UserService();
        service.setAttributesConverter(request -> attributes ->
                platformOf(request.getClientRegistration()) == SocialPlatform.TWITTER
                        && attributes.get("data") instanceof Map<?, ?> data ? stringKeys(data) : attributes);
        return service;
    }

    private static ClientRegistration facebook(SocialLoginProperties.Provider provider, String version) {
        return ClientRegistration.withRegistrationId(registrationId(SocialPlatform.FACEBOOK))
                .clientId(provider.clientId())
                .clientSecret(provider.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(CALLBACK)
                .scope("public_profile", "email")
                .authorizationUri("https://www.facebook.com/" + version + "/dialog/oauth")
                .tokenUri("https://graph.facebook.com/" + version + "/oauth/access_token")
                .userInfoUri("https://graph.facebook.com/" + version + "/me?fields=id,name,email,picture")
                .userNameAttributeName("id")
                .clientName("Facebook")
                .build();
    }

    private static ClientRegistration twitter(SocialLoginProperties.Provider provider) {
        return ClientRegistration.withRegistrationId(registrationId(SocialPlatform.TWITTER))
                .clientId(provider.clientId())
                .clientSecret(provider.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(CALLBACK)
                .scope("users.read", "tweet.read")
                .authorizationUri("https://x.com/i/oauth2/authorize")
                .tokenUri("https://api.x.com/2/oauth2/token")
                .userInfoUri("https://api.x.com/2/users/me?user.fields=profile_image_url")
                .userNameAttributeName("id")
                .clientName("X")
                // X requires PKCE (S256). Spring Security 7 already requires it for every client by default; said
                // here explicitly so a change of that default can't break the X login.
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
