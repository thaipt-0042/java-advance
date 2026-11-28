package com.sunasterisk.socialanalytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Query of {@code GET /api/v1/exchange-rates/usd-vnd}. A record like the other filters, so a malformed date is a
 * 422 with the field, not a bare 400.
 */
public record ExchangeRateQuery(
        @Schema(description = "Ngày của tỷ giá; mặc định hôm nay (giờ Việt Nam)", example = "2026-11-20")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
}
