package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.hibernate.validator.constraints.URL;

public record PostCreateRequest(
        @Schema(example = "FACEBOOK")
        @NotNull SocialPlatform platform,
        @Schema(description = "Id bài viết trên nền tảng; cùng nền tảng không được trùng", example = "1234567890")
        @NotBlank @Size(max = Post.EXTERNAL_ID_MAX_LENGTH) String externalId,
        @Schema(description = "Page / tài khoản đăng bài", example = "sun.asterisk")
        @NotBlank @Size(max = Post.ACCOUNT_NAME_MAX_LENGTH) String accountName,
        @Schema(example = "https://www.facebook.com/sun.asterisk/posts/1234567890")
        @Size(max = Post.URL_MAX_LENGTH) @URL(regexp = "^(https?://.*)?$") String url,
        @Size(max = Post.CONTENT_MAX_LENGTH) String content,
        @Schema(description = "Thời điểm đăng (ISO-8601)", example = "2026-03-01T03:00:00Z")
        Instant publishedAt) {
}
