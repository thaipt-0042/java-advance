package com.sunasterisk.socialanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A person who signs in through Facebook or Twitter (OAuth2, see #9). */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "provider", nullable = false, length = 20)
    private SocialPlatform provider;

    /** Id of the account at the provider; unique together with {@link #provider}. */
    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    /** Not unique: Twitter may not return an email, and one person can sign in with both providers. */
    @Column(name = "email")
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "avatar_url", length = 1000)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    protected User() {
    }

    public User(SocialPlatform provider, String providerUserId, String displayName) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.displayName = displayName;
    }

    public SocialPlatform getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }
}
