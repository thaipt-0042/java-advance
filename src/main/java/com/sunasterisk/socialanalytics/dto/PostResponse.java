package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public record PostResponse(
        Long id,
        SocialPlatform platform,
        String externalId,
        String accountName,
        String url,
        String content,
        Instant publishedAt,
        @Schema(description = "Chi phí quảng cáo (USD)") BigDecimal adCost,
        Instant createdAt,
        Instant updatedAt) {

    public static PostResponse from(Post post) {
        return new PostResponse(post.getId(), post.getPlatform(), post.getExternalId(), post.getAccountName(),
                post.getUrl(), post.getContent(), post.getPublishedAt(), post.getAdCost(), post.getCreatedAt(),
                post.getUpdatedAt());
    }
}
