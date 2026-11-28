package com.sunasterisk.socialanalytics.security;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import com.sunasterisk.socialanalytics.entity.UserRole;
import com.sunasterisk.socialanalytics.service.UserAccountService;
import com.sunasterisk.socialanalytics.service.UserAccountService.SocialProfile;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.StringUtils;

/**
 * Loads the profile from the provider ({@code delegate}, Spring Security's default user service), then creates or
 * updates the {@code users} row and sets the role from the admin list at every login (Q1). The provider's attributes
 * differ: Facebook answers {@code {id, name, email, picture: {data: {url}}}}, X (API v2) wraps the user in
 * {@code data}: {@code {data: {id, name, username, profile_image_url}}}; X's wrapper is removed by the delegate's
 * attributes converter ({@link SocialClientRegistrations}).
 */
class SocialOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final UserAccountService userAccountService;
    private final Set<SocialLoginProperties.AdminAccount> admins;

    SocialOAuth2UserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate,
            UserAccountService userAccountService, Set<SocialLoginProperties.AdminAccount> admins) {
        this.delegate = delegate;
        this.userAccountService = userAccountService;
        this.admins = admins;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        SocialPlatform platform = SocialClientRegistrations.platformOf(request.getClientRegistration());
        Map<String, Object> attributes = delegate.loadUser(request).getAttributes();
        String id = text(attributes.get("id"));
        if (id == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info",
                    platform + " did not return the account id", null));
        }
        String name = text(attributes.get("name"));
        if (name == null) {
            name = text(attributes.get("username"));
        }
        SocialProfile profile = new SocialProfile(platform, id, name == null ? id : name,
                text(attributes.get("email")), avatar(platform, attributes));
        UserRole role = admins.contains(new SocialLoginProperties.AdminAccount(platform, id))
                ? UserRole.ADMIN : UserRole.USER;
        User user = userAccountService.sync(profile, role);
        return new SocialUser(user);
    }

    private static @Nullable String avatar(SocialPlatform platform, Map<String, Object> attributes) {
        if (platform == SocialPlatform.TWITTER) {
            return text(attributes.get("profile_image_url"));
        }
        // Facebook: picture.data.url
        if (attributes.get("picture") instanceof Map<?, ?> picture && picture.get("data") instanceof Map<?, ?> data) {
            return text(data.get("url"));
        }
        return null;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value != null && StringUtils.hasText(value.toString()) ? value.toString().strip() : null;
    }
}
