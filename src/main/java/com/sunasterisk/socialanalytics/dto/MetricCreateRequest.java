package com.sunasterisk.socialanalytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

public record MetricCreateRequest(
        @Schema(description = "Id bài viết (không tồn tại thì 404)", example = "1")
        @NotNull Long postId,
        @Schema(description = "Thời điểm đo (ISO-8601); mỗi bài chỉ có một snapshot tại một thời điểm",
                example = "2026-03-01T03:00:00Z")
        @NotNull Instant capturedAt,
        @Schema(example = "120") @NotNull @PositiveOrZero Long likes,
        @Schema(example = "15") @NotNull @PositiveOrZero Long shares,
        @Schema(example = "8") @NotNull @PositiveOrZero Long comments,
        @Schema(description = "Số follower của tài khoản đăng bài tại thời điểm đo", example = "5400")
        @NotNull @PositiveOrZero Long followers) {
}
