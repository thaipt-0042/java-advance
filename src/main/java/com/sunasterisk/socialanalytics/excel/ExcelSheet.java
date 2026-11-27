package com.sunasterisk.socialanalytics.excel;

import com.sunasterisk.socialanalytics.exception.RowError;
import java.util.List;
import java.util.Set;

/**
 * Result of reading a sheet.
 *
 * @param columns   names of the record components whose column is in the header; a component whose column is
 *                  absent is null in every row, which is not the same as an empty cell in a present column
 * @param totalRows number of non-blank data rows
 * @param rows      rows without errors
 * @param errors    conversion and validation errors of the other rows
 */
public record ExcelSheet<T>(Set<String> columns, int totalRows, List<ExcelRow<T>> rows, List<RowError> errors) {
}
