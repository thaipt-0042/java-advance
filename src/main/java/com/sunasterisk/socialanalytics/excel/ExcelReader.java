package com.sunasterisk.socialanalytics.excel;

import com.sunasterisk.socialanalytics.exception.InvalidFileException;
import com.sunasterisk.socialanalytics.exception.RowError;
import com.sunasterisk.socialanalytics.exception.RowValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.poi.EmptyFileException;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JRuntimeException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Reads the first sheet of an .xlsx file into records whose components carry {@link ExcelColumn}: the first row is
 * the header, columns are matched by name (any order, ignoring case), and each cell is converted to the component
 * type by reflection, then the record is checked with Bean Validation.
 *
 * <ul>
 *   <li>Whole-file problems (not a readable .xlsx, missing required / unknown / repeated column, no data row) throw
 *       {@link InvalidFileException}; more than {@code maxRows} data rows throws {@link RowValidationException}.</li>
 *   <li>Cell problems don't stop the reading: the errors of every row are returned in {@link ExcelSheet#errors()}
 *       so the user can fix most of the file in one go. A row with a cell that can't be converted reports only those
 *       cells; Bean Validation runs on it once they are fixed.</li>
 *   <li>Supported component types: {@code String}, {@code Long}, {@code Instant} and enums. Text is stripped and an
 *       empty cell is null. A date cell has no time zone, so it is read in the application zone ({@link Clock}).</li>
 *   <li>Rows whose mapped cells are all empty are skipped (and not counted).</li>
 * </ul>
 *
 * <p>The workbook is loaded in memory ({@code XSSFWorkbook}); the upload size limit bounds it, and POI's zip bomb
 * check (on by default) rejects files that inflate abnormally.
 */
@Component
public class ExcelReader {

    /** Excel stores numbers as doubles: from 16 digits on, integers are no longer exact (Excel shows 15). */
    private static final BigDecimal MAX_EXACT_NUMBER = new BigDecimal("1e15");

    private final Validator validator;
    private final ZoneId zone;

    public ExcelReader(Validator validator, Clock clock) {
        this.validator = validator;
        this.zone = clock.getZone();
    }

    public <T extends Record> ExcelSheet<T> read(InputStream in, Class<T> rowType, int maxRows) {
        RecordMapping<T> mapping = RecordMapping.of(rowType);
        try (XSSFWorkbook workbook = open(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new InvalidFileException("File không có sheet nào");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Map<Integer, Column> columns = readHeader(sheet.getRow(sheet.getFirstRowNum()), mapping);
            List<Row> dataRows = dataRows(sheet, columns);
            if (dataRows.isEmpty()) {
                throw new InvalidFileException("File không có dòng dữ liệu nào");
            }
            if (dataRows.size() > maxRows) {
                throw new RowValidationException(
                        "File có %d dòng dữ liệu, tối đa %d dòng".formatted(dataRows.size(), maxRows), List.of());
            }
            List<ExcelRow<T>> rows = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            for (Row row : dataRows) {
                readRow(row, columns, mapping, rows, errors);
            }
            Set<String> present = columns.values().stream()
                    .map(column -> column.component().getName())
                    .collect(Collectors.toUnmodifiableSet());
            return new ExcelSheet<>(present, dataRows.size(), rows, errors);
        } catch (IOException ex) {
            // Also thrown by POI's zip bomb check while the workbook is read.
            throw notXlsx(ex);
        }
    }

    private static XSSFWorkbook open(InputStream in) throws IOException {
        try {
            return new XSSFWorkbook(in);
        } catch (EmptyFileException | UnsupportedFileFormatException | POIXMLException
                 | OpenXML4JRuntimeException ex) {
            // Empty, not a zip, .xls / encrypted (OLE2), or a zip that isn't a valid workbook.
            throw notXlsx(ex);
        }
    }

    private static InvalidFileException notXlsx(Exception cause) {
        return new InvalidFileException("File không phải file Excel .xlsx đọc được", cause);
    }

    private static Map<Integer, Column> readHeader(@Nullable Row header, RecordMapping<?> mapping) {
        if (header == null) {
            throw new InvalidFileException("File không có dòng tiêu đề");
        }
        Map<Integer, Column> columns = new HashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Cell cell : header) {
            String name = cell.getCellType() == CellType.STRING ? cell.getStringCellValue().strip() : "";
            if (name.isEmpty()) {
                if (cell.getCellType() != CellType.BLANK) {
                    throw new InvalidFileException(
                            "Tiêu đề cột %s phải là chữ".formatted(cell.getAddress().formatAsString()));
                }
                continue;
            }
            RecordComponent component = mapping.byColumn().get(name.toLowerCase(Locale.ROOT));
            if (component == null) {
                throw new InvalidFileException("Cột lạ: \"%s\". Các cột hợp lệ: %s"
                        .formatted(name, String.join(", ", mapping.columnNames())));
            }
            if (!seen.add(component.getName())) {
                throw new InvalidFileException("Cột \"%s\" xuất hiện hai lần".formatted(name));
            }
            columns.put(cell.getColumnIndex(),
                    new Column(columnName(component), component, mapping.indexOf(component)));
        }
        List<String> missing = mapping.requiredColumns().stream()
                .filter(required -> columns.values().stream().noneMatch(c -> c.name().equals(required)))
                .toList();
        if (!missing.isEmpty()) {
            throw new InvalidFileException("Thiếu cột bắt buộc: " + String.join(", ", missing));
        }
        return columns;
    }

    /** Rows after the header with at least one non-empty mapped cell. */
    private static List<Row> dataRows(Sheet sheet, Map<Integer, Column> columns) {
        List<Row> rows = new ArrayList<>();
        for (Row row : sheet) {
            if (row.getRowNum() > sheet.getFirstRowNum() && !isBlank(row, columns)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean isBlank(Row row, Map<Integer, Column> columns) {
        for (int index : columns.keySet()) {
            Cell cell = row.getCell(index);
            if (cell != null && !(cell.getCellType() == CellType.BLANK
                    || cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) {
                return false;
            }
        }
        return true;
    }

    private <T extends Record> void readRow(Row row, Map<Integer, Column> columns, RecordMapping<T> mapping,
            List<ExcelRow<T>> rows, List<RowError> errors) {
        int rowNumber = row.getRowNum() + 1;
        Object[] values = new Object[mapping.components().length];
        boolean converted = true;
        for (Map.Entry<Integer, Column> entry : columns.entrySet()) {
            Column column = entry.getValue();
            try {
                values[column.index()] = convert(row.getCell(entry.getKey()), column.component().getType());
            } catch (CellException ex) {
                errors.add(new RowError(rowNumber, column.name(), ex.getMessage()));
                converted = false;
            }
        }
        if (!converted) {
            return;
        }
        T value = mapping.newInstance(values);
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (violations.isEmpty()) {
            rows.add(new ExcelRow<>(rowNumber, value));
            return;
        }
        // Same order every time: by column as declared in the record, row-level rules last.
        violations.stream()
                .sorted(Comparator.comparingInt((ConstraintViolation<T> violation) ->
                                mapping.positionOf(violation.getPropertyPath()))
                        .thenComparing(ConstraintViolation::getMessage))
                .map(violation -> new RowError(rowNumber, mapping.columnOf(violation.getPropertyPath()),
                        violation.getMessage()))
                .forEach(errors::add);
    }

    private @Nullable Object convert(@Nullable Cell cell, Class<?> type) {
        if (cell == null) {
            return null;
        }
        CellType cellType = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (cellType == CellType.BLANK) {
            return null;
        }
        if (cellType == CellType.ERROR) {
            throw new CellException("Ô bị lỗi công thức");
        }
        if (type == String.class) {
            return toText(cell, cellType);
        }
        if (type == Long.class) {
            return toLong(cell, cellType);
        }
        if (type == Instant.class) {
            return toInstant(cell, cellType);
        }
        return toEnum(cell, cellType, type);
    }

    private static @Nullable String toText(Cell cell, CellType cellType) {
        return switch (cellType) {
            case STRING -> blankToNull(cell.getStringCellValue());
            case NUMERIC -> {
                BigDecimal number = BigDecimal.valueOf(cell.getNumericCellValue());
                if (number.abs().compareTo(MAX_EXACT_NUMBER) >= 0) {
                    throw new CellException("Số từ 16 chữ số trở lên đã bị Excel làm tròn, hãy nhập ô này dạng text");
                }
                // 1234567890 instead of 1.23456789E9 or 1234567890.0
                yield number.stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> throw new CellException("Không đọc được giá trị của ô");
        };
    }

    private static @Nullable Long toLong(Cell cell, CellType cellType) {
        try {
            BigDecimal number = switch (cellType) {
                case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
                case STRING -> {
                    String text = blankToNull(cell.getStringCellValue());
                    yield text == null ? null : new BigDecimal(text);
                }
                default -> throw new CellException("Phải là số nguyên");
            };
            return number == null ? null : number.longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new CellException("Phải là số nguyên");
        }
    }

    private @Nullable Instant toInstant(Cell cell, CellType cellType) {
        if (cellType == CellType.NUMERIC) {
            if (!DateUtil.isCellDateFormatted(cell)) {
                throw new CellException("Phải là ngày giờ (ô dạng Date, hoặc text như 2026-03-01 10:00)");
            }
            LocalDateTime local = cell.getLocalDateTimeCellValue();
            if (local == null) {
                throw new CellException("Ngày giờ không hợp lệ");
            }
            return local.atZone(zone).toInstant();
        }
        if (cellType != CellType.STRING) {
            throw new CellException("Phải là ngày giờ (ô dạng Date, hoặc text như 2026-03-01 10:00)");
        }
        String text = blankToNull(cell.getStringCellValue());
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            try {
                // "2026-03-01 10:00" or "2026-03-01T10:00:00": a local time in the application zone
                return LocalDateTime.parse(text.replace(' ', 'T')).atZone(zone).toInstant();
            } catch (DateTimeParseException ex) {
                throw new CellException(
                        "Ngày giờ không đúng định dạng (ví dụ 2026-03-01 10:00 hoặc 2026-03-01T03:00:00Z)");
            }
        }
    }

    private static @Nullable Object toEnum(Cell cell, CellType cellType, Class<?> type) {
        Object[] constants = type.getEnumConstants();
        String text = cellType == CellType.STRING ? blankToNull(cell.getStringCellValue()) : null;
        if (cellType == CellType.STRING && text == null) {
            return null;
        }
        // Case-sensitive, like the JSON API.
        return Arrays.stream(constants)
                .filter(constant -> ((Enum<?>) constant).name().equals(text))
                .findFirst()
                .orElseThrow(() -> new CellException("Phải là một trong: " + Arrays.stream(constants)
                        .map(constant -> ((Enum<?>) constant).name())
                        .collect(Collectors.joining(", "))));
    }

    private static @Nullable String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static String columnName(RecordComponent component) {
        return component.getAnnotation(ExcelColumn.class).name();
    }

    /** A header column: its name, the record component it fills and that component's index. */
    private record Column(String name, RecordComponent component, int index) {
    }

    /** A cell that can't be converted to the component type; the message is shown to the user. */
    private static final class CellException extends RuntimeException {

        CellException(String message) {
            super(message, null, false, false);
        }
    }

    /** Reflection metadata of a row record. */
    private record RecordMapping<T extends Record>(RecordComponent[] components, Constructor<T> constructor,
            Map<String, RecordComponent> byColumn, List<String> columnNames, List<String> requiredColumns) {

        static <T extends Record> RecordMapping<T> of(Class<T> type) {
            RecordComponent[] components = type.getRecordComponents();
            Map<String, RecordComponent> byColumn = new HashMap<>();
            List<String> names = new ArrayList<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : components) {
                ExcelColumn column = component.getAnnotation(ExcelColumn.class);
                if (column == null) {
                    throw new IllegalStateException("%s.%s has no @ExcelColumn"
                            .formatted(type.getSimpleName(), component.getName()));
                }
                if (byColumn.put(column.name().toLowerCase(Locale.ROOT), component) != null) {
                    throw new IllegalStateException("Duplicate @ExcelColumn \"%s\" in %s"
                            .formatted(column.name(), type.getSimpleName()));
                }
                Class<?> componentType = component.getType();
                if (!(componentType == String.class || componentType == Long.class || componentType == Instant.class
                        || componentType.isEnum())) {
                    throw new IllegalStateException("%s.%s: unsupported type %s".formatted(type.getSimpleName(),
                            component.getName(), componentType.getSimpleName()));
                }
                names.add(column.name());
                if (column.required()) {
                    required.add(column.name());
                }
            }
            Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            try {
                Constructor<T> constructor = type.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return new RecordMapping<>(components, constructor, byColumn, List.copyOf(names),
                        List.copyOf(required));
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException(ex);
            }
        }

        int indexOf(RecordComponent component) {
            return Arrays.asList(components).indexOf(component);
        }

        T newInstance(Object[] values) {
            try {
                return constructor.newInstance(values);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Cannot create " + constructor.getDeclaringClass().getName(), ex);
            }
        }

        /** Index of the component a violation is on; after every component for a rule on the whole row. */
        int positionOf(Path path) {
            String property = path.iterator().next().getName();
            for (int i = 0; i < components.length; i++) {
                if (components[i].getName().equals(property)) {
                    return i;
                }
            }
            return components.length;
        }

        /** Column of the component a violation is on; null for a rule on the whole row (e.g. {@code @AssertTrue}). */
        @Nullable String columnOf(Path path) {
            String property = path.iterator().next().getName();
            return Arrays.stream(components)
                    .filter(component -> component.getName().equals(property))
                    .map(ExcelReader::columnName)
                    .findFirst()
                    .orElse(null);
        }
    }
}
