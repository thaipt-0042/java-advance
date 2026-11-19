package com.sunasterisk.socialanalytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import java.time.Instant;

/** Filters of {@code GET /api/v1/metrics}; every filter is optional. */
public record MetricFilter(
        @Schema(description = "Chỉ lấy snapshot của bài viết này (không tồn tại thì 404)")
        Long postId,
        @Schema(description = "Từ thời điểm (bao gồm), ISO-8601", example = "2026-03-01T00:00:00Z")
        Instant from,
        @Schema(description = "Đến thời điểm (không bao gồm), ISO-8601", example = "2026-03-08T00:00:00Z")
        Instant to) {

    @Schema(hidden = true)
    @AssertTrue(message = "from phải trước to")
    public boolean isRangeValid() {
        return from == null || to == null || from.isBefore(to);
    }
}
