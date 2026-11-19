package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Filters of {@code GET /api/v1/posts}; every filter is optional. */
public record PostFilter(
        @Schema(description = "Lọc theo nền tảng")
        SocialPlatform platform,
        @Schema(description = "Từ khóa trong nội dung hoặc tên tài khoản (không phân biệt hoa thường, dấu)")
        @Size(max = 100) String q) {
}
