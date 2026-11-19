package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.Post;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.hibernate.validator.constraints.URL;

/** Replaces the editable fields of a post. Platform and external id identify the post and can't be changed. */
public record PostUpdateRequest(
        @Schema(example = "sun.asterisk")
        @NotBlank @Size(max = Post.ACCOUNT_NAME_MAX_LENGTH) String accountName,
        @Size(max = Post.URL_MAX_LENGTH) @URL(regexp = Post.URL_PATTERN) String url,
        @Size(max = Post.CONTENT_MAX_LENGTH) String content,
        Instant publishedAt) {
}
