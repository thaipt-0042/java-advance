package com.sunasterisk.socialanalytics.security;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The principal of a Facebook / X login. It lives in the login session, which is stored in MySQL (Spring Session
 * JDBC), so it is small and serializable and holds no JPA entity: the {@code users} id, the provider identity, the
 * name to show and the role at login time.
 *
 * <p>{@link #getName()} is the provider's user id: the {@code principal_name} under which Spring Security stores the
 * account's token.
 */
public final class SocialUser implements OAuth2User, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long userId;
    private final SocialPlatform provider;
    private final String providerUserId;
    private final String displayName;
    private final String role;

    public SocialUser(User user) {
        this.userId = user.getId();
        this.provider = user.getProvider();
        this.providerUserId = user.getProviderUserId();
        this.displayName = user.getDisplayName();
        this.role = user.getRole().name();
    }

    /** Id of the row in {@code users}, e.g. for {@code import_batches.uploaded_by}. */
    public long userId() {
        return userId;
    }

    public SocialPlatform provider() {
        return provider;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String getName() {
        return providerUserId;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("id", providerUserId, "name", displayName, "provider", provider.name());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String toString() {
        return "SocialUser[" + provider + ":" + providerUserId + ", " + role + "]";
    }
}
