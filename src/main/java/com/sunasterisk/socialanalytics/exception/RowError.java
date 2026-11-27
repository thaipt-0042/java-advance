package com.sunasterisk.socialanalytics.exception;

import org.jspecify.annotations.Nullable;

/**
 * An error in one row of an uploaded file.
 *
 * @param row     row number as shown in Excel (the header is row 1)
 * @param column  column name from the header, or null when the error concerns the whole row
 * @param message what is wrong
 */
public record RowError(int row, @Nullable String column, String message) {
}
