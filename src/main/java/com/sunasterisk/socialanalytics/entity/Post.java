package com.sunasterisk.socialanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A post on a social network whose interactions are tracked.
 *
 * <p>Metrics are not mapped as a collection here: a post collects one snapshot per crawl
 * (hourly), so they are read through {@code SocialMetricRepository} with paging/time ranges.
 * Deleting a post deletes its metrics through the database foreign key ({@code ON DELETE CASCADE}).
 */
@Entity
@Table(name = "posts")
public class Post extends BaseEntity {

    /** Column lengths, shared with request DTOs so validation matches the schema. */
    public static final int EXTERNAL_ID_MAX_LENGTH = 100;
    public static final int ACCOUNT_NAME_MAX_LENGTH = 100;
    public static final int URL_MAX_LENGTH = 1000;
    public static final int CONTENT_MAX_LENGTH = 5000;
    /** Accepted {@code url}: http(s) only; empty is allowed and stored as null. */
    public static final String URL_PATTERN = "^(https?://.*)?$";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "platform", nullable = false, length = 20)
    private SocialPlatform platform;

    /** Id of the post on the platform; unique together with {@link #platform}. */
    @Column(name = "external_id", nullable = false, length = EXTERNAL_ID_MAX_LENGTH)
    private String externalId;

    /** Page / account handle that published the post. */
    @Column(name = "account_name", nullable = false, length = ACCOUNT_NAME_MAX_LENGTH)
    private String accountName;

    @Column(name = "url", length = URL_MAX_LENGTH)
    private String url;

    @Column(name = "content", length = CONTENT_MAX_LENGTH)
    private String content;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected Post() {
    }

    public Post(SocialPlatform platform, String externalId, String accountName) {
        this.platform = platform;
        this.externalId = externalId;
        this.accountName = accountName;
    }

    public SocialPlatform getPlatform() {
        return platform;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
