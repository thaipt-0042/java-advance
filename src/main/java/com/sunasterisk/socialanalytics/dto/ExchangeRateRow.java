package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.excel.ExcelColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The "ExchangeRates" sheet of the report: the rate used for {@code adCostVnd} (Q7), or a note that the service
 * could not give one.
 */
public record ExchangeRateRow(
        @ExcelColumn(name = "date") LocalDate date,
        @ExcelColumn(name = "from") String from,
        @ExcelColumn(name = "to") String to,
        @ExcelColumn(name = "rate") @Nullable BigDecimal rate,
        @ExcelColumn(name = "note") @Nullable String note) {
}
