package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Filters of {@code GET /api/v1/export-report}; every filter is optional. {@code from} / {@code to} are days of
 * {@code publishedAt} in the application time zone, both included; with either of them, posts without
 * {@code publishedAt} are left out.
 */
public record ReportFilter(
        @Schema(description = "Ngày đăng từ (bao gồm), theo giờ Việt Nam", example = "2026-11-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @Schema(description = "Ngày đăng đến (bao gồm), theo giờ Việt Nam", example = "2026-11-30")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @Schema(description = "Lọc theo nền tảng")
        SocialPlatform platform) {

    /** Longest range when both ends are given, in days (both ends included). */
    public static final int MAX_DAYS = 366;

    @Schema(hidden = true)
    @AssertTrue(message = "from không được sau to")
    public boolean isRangeOrdered() {
        return from == null || to == null || !from.isAfter(to);
    }

    @Schema(hidden = true)
    @AssertTrue(message = "Khoảng ngày tối đa " + MAX_DAYS + " ngày")
    public boolean isRangeShortEnough() {
        return from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) < MAX_DAYS;
    }
}
