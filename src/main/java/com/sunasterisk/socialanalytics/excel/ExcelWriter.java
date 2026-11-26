package com.sunasterisk.socialanalytics.excel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Writes records whose components carry {@link ExcelColumn} as sheets of a streaming workbook: one header row with
 * the column names (bold, frozen), then one row per record, columns in the order of the record components. The
 * values are read by reflection through the record accessors.
 *
 * <ul>
 *   <li>Supported component types: {@code String}, {@code Long} / {@code Integer}, {@code Instant},
 *       {@code LocalDate} and enums. {@code null} leaves the cell empty.</li>
 *   <li>Text is always written as a text cell, never as a formula: a value such as {@code =HYPERLINK(...)} coming
 *       from user data is shown as is, not run by Excel.</li>
 *   <li>An {@code Instant} becomes a date cell in the application time zone ({@link Clock}), the zone in which
 *       {@link ExcelReader} reads date cells, so an exported date imports back to the same instant (to the
 *       millisecond, Excel's precision).</li>
 * </ul>
 *
 * <p>The workbook is an {@link SXSSFWorkbook}: only the last rows of each sheet stay in memory, the others are
 * flushed to compressed temporary files, so a large export doesn't hold the whole file in the heap. Use
 * {@link ExcelExport} to write it out and delete those temporary files.
 */
@Component
public class ExcelWriter {

    /** Rows kept in memory per sheet before the oldest are flushed to the temporary file. */
    private static final int ROWS_IN_MEMORY = 100;

    private static final int DEFAULT_WIDTH = 16;
    private static final int TEXT_WIDTH = 40;

    private final ZoneId zone;

    public ExcelWriter(Clock clock) {
        this.zone = clock.getZone();
    }

    public ExcelExport newWorkbook() {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROWS_IN_MEMORY);
        workbook.setCompressTempFiles(true);
        return new ExcelExport(workbook);
    }

    /** Adds a sheet with the header of {@code rowType}; append the rows to the returned writer. */
    public <T extends Record> SheetWriter<T> addSheet(ExcelExport export, String name, Class<T> rowType) {
        Columns columns = Columns.of(rowType);
        SXSSFWorkbook workbook = export.workbook();
        Sheet sheet = workbook.createSheet(name);
        Styles styles = Styles.of(workbook);
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.names().length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns.names()[i]);
            cell.setCellStyle(styles.header());
            sheet.setColumnWidth(i, columns.widths()[i] * 256);
        }
        sheet.createFreezePane(0, 1);
        return new SheetWriter<>(sheet, columns, styles, zone);
    }

    /** Appends rows to one sheet, in order. Not thread-safe. */
    public static final class SheetWriter<T extends Record> {

        private final Sheet sheet;
        private final Columns columns;
        private final Styles styles;
        private final ZoneId zone;
        private int nextRow = 1;

        private SheetWriter(Sheet sheet, Columns columns, Styles styles, ZoneId zone) {
            this.sheet = sheet;
            this.columns = columns;
            this.styles = styles;
            this.zone = zone;
        }

        public void append(T value) {
            Row row = sheet.createRow(nextRow++);
            for (int i = 0; i < columns.accessors().length; i++) {
                Object cellValue = columns.read(value, i);
                if (cellValue != null) {
                    write(row.createCell(i), cellValue);
                }
            }
        }

        /** Data rows written so far. */
        public int rowCount() {
            return nextRow - 1;
        }

        private void write(Cell cell, Object value) {
            switch (value) {
                case String text -> cell.setCellValue(text);
                case Long number -> cell.setCellValue(number);
                case Integer number -> cell.setCellValue(number);
                case Instant instant -> {
                    cell.setCellValue(LocalDateTime.ofInstant(instant, zone));
                    cell.setCellStyle(styles.dateTime());
                }
                case LocalDate date -> {
                    cell.setCellValue(date);
                    cell.setCellStyle(styles.date());
                }
                case Enum<?> constant -> cell.setCellValue(constant.name());
                default -> throw new IllegalStateException("Unsupported type " + value.getClass().getName());
            }
        }
    }

    private record Styles(CellStyle header, CellStyle dateTime, CellStyle date) {

        static Styles of(SXSSFWorkbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            CellStyle dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            return new Styles(header, dateTime, date);
        }
    }

    /** Reflection metadata of a row record: header names, accessors and column widths. */
    private record Columns(String[] names, Method[] accessors, int[] widths) {

        static Columns of(Class<? extends Record> type) {
            RecordComponent[] components = type.getRecordComponents();
            String[] names = new String[components.length];
            Method[] accessors = new Method[components.length];
            int[] widths = new int[components.length];
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                ExcelColumn column = component.getAnnotation(ExcelColumn.class);
                if (column == null) {
                    throw new IllegalStateException("%s.%s has no @ExcelColumn"
                            .formatted(type.getSimpleName(), component.getName()));
                }
                if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("Duplicate @ExcelColumn \"%s\" in %s"
                            .formatted(column.name(), type.getSimpleName()));
                }
                Class<?> componentType = component.getType();
                if (!isSupported(componentType)) {
                    throw new IllegalStateException("%s.%s: unsupported type %s".formatted(type.getSimpleName(),
                            component.getName(), componentType.getSimpleName()));
                }
                names[i] = column.name();
                accessors[i] = component.getAccessor();
                accessors[i].setAccessible(true);
                widths[i] = componentType == String.class ? TEXT_WIDTH : DEFAULT_WIDTH;
            }
            return new Columns(names, accessors, widths);
        }

        private static boolean isSupported(Class<?> type) {
            return Arrays.asList(String.class, Long.class, Integer.class, Instant.class, LocalDate.class)
                    .contains(type) || type.isEnum();
        }

        @Nullable Object read(Record value, int index) {
            try {
                return accessors[index].invoke(value);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Cannot read " + accessors[index], ex);
            }
        }
    }
}
