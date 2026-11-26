package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.Post;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.hibernate.validator.constraints.URL;

/**
 * Replaces the editable fields of a post. Platform and external id identify the post and can't be changed.
 * {@code updatedAt} is the value the client read: if the post changed since, the update is rejected with 409.
 */
public record PostUpdateRequest(
        @Schema(example = "sun.asterisk")
        @NotBlank @Size(max = Post.ACCOUNT_NAME_MAX_LENGTH) String accountName,
        @Size(max = Post.URL_MAX_LENGTH) @URL(regexp = Post.URL_PATTERN) String url,
        @Size(max = Post.CONTENT_MAX_LENGTH) String content,
        Instant publishedAt,
        @Schema(description = "updatedAt của bài viết lúc client đọc", example = "2026-03-01T03:00:00.123456Z")
        @NotNull Instant updatedAt) {
}
