package com.sunasterisk.socialanalytics.excel;

/**
 * A data row converted and validated without error.
 *
 * @param rowNumber row number as shown in Excel (the header is row 1)
 */
public record ExcelRow<T>(int rowNumber, T value) {
}
