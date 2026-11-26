package com.sunasterisk.socialanalytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * Replaces a snapshot's time and counters. The post it belongs to can't be changed.
 * {@code updatedAt} is the value the client read: if the snapshot changed since, the update is rejected with 409.
 */
public record MetricUpdateRequest(
        @Schema(example = "2026-03-01T03:00:00Z") @NotNull Instant capturedAt,
        @Schema(example = "120") @NotNull @PositiveOrZero Long likes,
        @Schema(example = "15") @NotNull @PositiveOrZero Long shares,
        @Schema(example = "8") @NotNull @PositiveOrZero Long comments,
        @Schema(example = "5400") @NotNull @PositiveOrZero Long followers,
        @Schema(description = "updatedAt của snapshot lúc client đọc", example = "2026-03-01T03:00:00.123456Z")
        @NotNull Instant updatedAt) {
}
