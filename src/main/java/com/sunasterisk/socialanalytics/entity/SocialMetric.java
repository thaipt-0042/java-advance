package com.sunasterisk.socialanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Snapshot of a post's interaction counters at {@link #capturedAt}.
 *
 * <p>{@link #followers} is the follower count of the post's account at capture time
 * (see docs/01-thiet-ke-csdl.md for why it lives here).
 */
@Entity
@Table(name = "social_metrics")
public class SocialMetric extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(name = "likes", nullable = false)
    private long likes;

    @Column(name = "shares", nullable = false)
    private long shares;

    @Column(name = "comments", nullable = false)
    private long comments;

    @Column(name = "followers", nullable = false)
    private long followers;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected SocialMetric() {
    }

    public SocialMetric(Post post, Instant capturedAt) {
        this.post = post;
        this.capturedAt = capturedAt;
    }

    public Post getPost() {
        return post;
    }

    public long getLikes() {
        return likes;
    }

    public void setLikes(long likes) {
        this.likes = likes;
    }

    public long getShares() {
        return shares;
    }

    public void setShares(long shares) {
        this.shares = shares;
    }

    public long getComments() {
        return comments;
    }

    public void setComments(long comments) {
        this.comments = comments;
    }

    public long getFollowers() {
        return followers;
    }

    public void setFollowers(long followers) {
        this.followers = followers;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }
}
