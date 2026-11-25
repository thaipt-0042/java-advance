package com.sunasterisk.socialanalytics.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds Excel files in memory for tests, so no binary fixtures are committed. Cell values: {@code String},
 * {@code Number}, {@code Boolean}, {@code LocalDateTime} (a date cell), {@link Formula}, or {@code null} for no cell.
 */
public final class Xlsx {

    public static final List<String> POST_HEADER = List.of("platform", "externalId", "accountName", "url",
            "content", "publishedAt", "likes", "shares", "comments", "followers");

    /** A formula cell; its cached value is computed before the file is written. */
    public record Formula(String formula) {
    }

    private Xlsx() {
    }

    /** An .xlsx file whose first sheet has these rows (the first one usually being the header). */
    public static byte[] of(List<?>... rows) {
        return write(new XSSFWorkbook(), Arrays.asList(rows));
    }

    /** The same content as a legacy .xls (OLE2) file. */
    public static byte[] xls(List<?>... rows) {
        return write(new HSSFWorkbook(), Arrays.asList(rows));
    }

    public static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    /** A file with the full post header and {@code count} posts {@code <prefix>-1..n}, with metrics. */
    public static byte[] posts(String prefix, int count) {
        List<List<?>> rows = new ArrayList<>();
        rows.add(POST_HEADER);
        for (int i = 1; i <= count; i++) {
            rows.add(row("FACEBOOK", prefix + "-" + i, "page-" + i, "https://example.com/" + prefix + "/" + i,
                    "content " + i, "2026-03-01T03:00:00Z", i, 2, 3, 100));
        }
        return of(rows.toArray(List<?>[]::new));
    }

    private static byte[] write(Workbook workbook, List<? extends List<?>> rows) {
        try (workbook; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("posts");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            boolean hasFormula = false;
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                List<?> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    Object value = values.get(c);
                    if (value == null) {
                        continue;
                    }
                    Cell cell = row.createCell(c);
                    switch (value) {
                        case String text -> cell.setCellValue(text);
                        case Number number -> cell.setCellValue(number.doubleValue());
                        case Boolean bool -> cell.setCellValue(bool);
                        case LocalDateTime dateTime -> {
                            cell.setCellValue(dateTime);
                            cell.setCellStyle(dateStyle);
                        }
                        case Formula formula -> {
                            cell.setCellFormula(formula.formula());
                            hasFormula = true;
                        }
                        default -> throw new IllegalArgumentException(value.getClass().getName());
                    }
                }
            }
            if (hasFormula) {
                workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
