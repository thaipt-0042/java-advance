package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Filters of {@code GET /api/v1/chart-data}: days in the application time zone, both included; by default the last
 * {@value #DEFAULT_DAYS} days up to today.
 */
public record ChartDataFilter(
        @Schema(description = "Từ ngày (bao gồm), theo giờ Việt Nam; mặc định 6 ngày trước `to`",
                example = "2026-11-14")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @Schema(description = "Đến ngày (bao gồm), theo giờ Việt Nam; mặc định hôm nay", example = "2026-11-20")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @Schema(description = "Chỉ một nền tảng; mặc định cả hai")
        SocialPlatform platform) {

    public static final int DEFAULT_DAYS = 7;
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
