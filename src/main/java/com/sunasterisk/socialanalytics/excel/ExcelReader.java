package com.sunasterisk.socialanalytics.excel;

import com.sunasterisk.socialanalytics.exception.InvalidFileException;
import com.sunasterisk.socialanalytics.exception.RowError;
import com.sunasterisk.socialanalytics.exception.RowValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.poi.EmptyFileException;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JRuntimeException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

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
 * <p>The sheet is streamed, not loaded as a workbook: the upload is copied to a temporary file, opened read-only
 * (random access to the zip entries) and the sheet XML is parsed with SAX, keeping the raw value of the mapped cells
 * only. Reading stops at data row {@code maxRows + 1}, so the heap holds at most {@code maxRows} rows plus the
 * shared strings table, whatever the file contains. POI's zip bomb check (inflate ratio) stays on, and each zip
 * entry may inflate to {@link #MAX_ENTRY_SIZE} at most.
 */
@Component
public class ExcelReader {

    /**
     * Largest inflated zip entry (sheet XML, shared strings). A legitimate import of 5,000 rows with long content
     * stays well below; POI's default is 4GB. Process-wide: it applies to every .xlsx POI reads in this JVM.
     */
    static final long MAX_ENTRY_SIZE = 40L * 1024 * 1024;

    static {
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);
    }

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
        java.nio.file.Path file = copyToTempFile(in);
        try {
            SheetContent content = readFirstSheet(file, mapping, maxRows);
            if (content.rows().isEmpty()) {
                throw new InvalidFileException("File không có dòng dữ liệu nào");
            }
            List<ExcelRow<T>> rows = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            for (RawRow row : content.rows()) {
                readRow(row, content.columns(), mapping, content.date1904(), rows, errors);
            }
            Set<String> present = content.columns().values().stream()
                    .map(column -> column.component().getName())
                    .collect(Collectors.toUnmodifiableSet());
            return new ExcelSheet<>(present, content.rows().size(), rows, errors);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ex) {
                file.toFile().deleteOnExit();
            }
        }
    }

    private static java.nio.file.Path copyToTempFile(InputStream in) {
        try {
            java.nio.file.Path file = Files.createTempFile("import-", ".xlsx");
            try {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                return file;
            } catch (IOException ex) {
                Files.deleteIfExists(file);
                throw ex;
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot store the uploaded file", ex);
        }
    }

    private static SheetContent readFirstSheet(java.nio.file.Path file, RecordMapping<?> mapping, int maxRows) {
        OPCPackage pkg = open(file);
        try {
            XSSFReader reader = new XSSFReader(pkg);
            boolean date1904 = isDate1904(reader);
            XSSFReader.SheetIterator sheets = reader.getSheetIterator();
            if (!sheets.hasNext()) {
                throw new InvalidFileException("File không có sheet nào");
            }
            SheetHandler handler = new SheetHandler(mapping, new ReadOnlySharedStringsTable(pkg, false),
                    reader.getStylesTable(), maxRows);
            try (InputStream sheet = sheets.next()) {
                parse(sheet, handler);
            }
            return new SheetContent(handler.columns(), handler.rows(), date1904);
        } catch (IOException | OpenXML4JException | SAXException | POIXMLException
                 | OpenXML4JRuntimeException ex) {
            // IOException is also what POI's zip bomb check throws while an entry is inflated.
            throw notXlsx(ex);
        } finally {
            pkg.revert();
        }
    }

    private static OPCPackage open(java.nio.file.Path file) {
        try {
            return OPCPackage.open(file.toFile(), PackageAccess.READ);
        } catch (OpenXML4JException | EmptyFileException | UnsupportedFileFormatException | POIXMLException
                 | OpenXML4JRuntimeException ex) {
            // Empty, not a zip, .xls / encrypted (OLE2), or a zip that isn't an OOXML package.
            throw notXlsx(ex);
        }
    }

    /** Parses with POI's hardened parser (no DTD, no external entities); our own exceptions pass through. */
    private static void parse(InputStream xml, DefaultHandler handler) throws IOException, SAXException {
        try {
            XMLReader parser = XMLHelper.newXMLReader();
            parser.setContentHandler(handler);
            parser.parse(new InputSource(xml));
        } catch (javax.xml.parsers.ParserConfigurationException ex) {
            throw new IllegalStateException(ex);
        } catch (SAXException ex) {
            if (ex.getException() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    /** Whether dates are counted from 1904 ({@code <workbookPr date1904="1"/>}, old Mac files) instead of 1900. */
    private static boolean isDate1904(XSSFReader reader) throws IOException, OpenXML4JException, SAXException {
        boolean[] date1904 = {false};
        try (InputStream workbook = reader.getWorkbookData()) {
            parse(workbook, new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    if (localName.equals("workbookPr")) {
                        String value = attributes.getValue("date1904");
                        date1904[0] = "1".equals(value) || "true".equalsIgnoreCase(value);
                    }
                }
            });
        }
        return date1904[0];
    }

    private static InvalidFileException notXlsx(Exception cause) {
        return new InvalidFileException("File không phải file Excel .xlsx đọc được", cause);
    }

    private static boolean isBlank(RawRow row) {
        return row.cells().values().stream().allMatch(cell -> cell.kind() == Kind.BLANK
                || cell.kind() == Kind.STRING && !StringUtils.hasText(cell.text()));
    }

    private <T extends Record> void readRow(RawRow row, Map<Integer, Column> columns, RecordMapping<T> mapping,
            boolean date1904, List<ExcelRow<T>> rows, List<RowError> errors) {
        int rowNumber = row.number();
        Object[] values = new Object[mapping.components().length];
        boolean converted = true;
        for (Map.Entry<Integer, Column> entry : columns.entrySet()) {
            Column column = entry.getValue();
            try {
                values[column.index()] = convert(row.cells().get(entry.getKey()), column.component().getType(),
                        date1904);
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

    private @Nullable Object convert(@Nullable RawCell cell, Class<?> type, boolean date1904) {
        if (cell == null || cell.kind() == Kind.BLANK) {
            return null;
        }
        if (cell.kind() == Kind.ERROR) {
            throw new CellException("Ô bị lỗi công thức");
        }
        if (cell.kind() == Kind.MALFORMED) {
            // e.g. a numeric cell holding text (<c r="B2"><v>abc</v></c>) or an unknown cell type, written by
            // another tool or edited by hand
            throw new CellException("Không đọc được giá trị của ô");
        }
        if (type == String.class) {
            return toText(cell);
        }
        if (type == Long.class) {
            return toLong(cell);
        }
        if (type == Instant.class) {
            return toInstant(cell, date1904);
        }
        return toEnum(cell, type);
    }

    private static @Nullable String toText(RawCell cell) {
        return switch (cell.kind()) {
            case STRING -> blankToNull(cell.text());
            case NUMERIC -> {
                BigDecimal number = new BigDecimal(cell.text());
                if (number.abs().compareTo(MAX_EXACT_NUMBER) >= 0) {
                    throw new CellException("Số từ 16 chữ số trở lên đã bị Excel làm tròn, hãy nhập ô này dạng text");
                }
                // 1234567890 instead of 1.23456789E9 or 1234567890.0
                yield number.stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.isTrue());
            default -> throw new CellException("Không đọc được giá trị của ô");
        };
    }

    private static @Nullable Long toLong(RawCell cell) {
        try {
            BigDecimal number = switch (cell.kind()) {
                case NUMERIC -> new BigDecimal(cell.text());
                case STRING -> {
                    String text = blankToNull(cell.text());
                    yield text == null ? null : new BigDecimal(text);
                }
                default -> throw new CellException("Phải là số nguyên");
            };
            return number == null ? null : number.longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new CellException("Phải là số nguyên");
        }
    }

    private @Nullable Instant toInstant(RawCell cell, boolean date1904) {
        if (cell.kind() == Kind.NUMERIC) {
            if (!cell.dateFormatted()) {
                throw new CellException("Phải là ngày giờ (ô dạng Date, hoặc text như 2026-03-01 10:00)");
            }
            double serial = Double.parseDouble(cell.text());
            LocalDateTime local = DateUtil.isValidExcelDate(serial)
                    ? DateUtil.getLocalDateTime(serial, date1904) : null;
            if (local == null) {
                throw new CellException("Ngày giờ không hợp lệ");
            }
            return local.atZone(zone).toInstant();
        }
        if (cell.kind() != Kind.STRING) {
            throw new CellException("Phải là ngày giờ (ô dạng Date, hoặc text như 2026-03-01 10:00)");
        }
        String text = blankToNull(cell.text());
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

    private static @Nullable Object toEnum(RawCell cell, Class<?> type) {
        Object[] constants = type.getEnumConstants();
        String text = cell.kind() == Kind.STRING ? blankToNull(cell.text()) : null;
        if (cell.kind() == Kind.STRING && text == null) {
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

    private static @Nullable String blankToNull(@Nullable String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static String columnName(RecordComponent component) {
        return component.getAnnotation(ExcelColumn.class).name();
    }

    /** What the SAX pass kept: header columns by index, the non-blank data rows, and the workbook date system. */
    private record SheetContent(Map<Integer, Column> columns, List<RawRow> rows, boolean date1904) {
    }

    /** A data row: its Excel row number (1-based) and its mapped cells by column index. */
    private record RawRow(int number, Map<Integer, RawCell> cells) {
    }

    private enum Kind { BLANK, STRING, NUMERIC, BOOLEAN, ERROR, MALFORMED }

    /**
     * The raw value of a cell as stored in the sheet: text for strings (shared strings resolved), the number as
     * written in the XML for numbers (already checked to parse), {@code "1"} / {@code "0"} for booleans.
     */
    private record RawCell(Kind kind, @Nullable String text, boolean dateFormatted) {

        static final RawCell BLANK = new RawCell(Kind.BLANK, null, false);
        static final RawCell MALFORMED = new RawCell(Kind.MALFORMED, null, false);

        boolean isTrue() {
            return "1".equals(text);
        }
    }

    /**
     * SAX handler of the sheet XML. The first {@code <row>} is the header: each cell is checked as it is read.
     * After that only cells in header columns are kept, and reading stops at data row {@code maxRows + 1}.
     */
    private static final class SheetHandler extends DefaultHandler {

        private final RecordMapping<?> mapping;
        private final SharedStrings sharedStrings;
        private final @Nullable StylesTable styles;
        private final int maxRows;
        private final Map<Integer, Boolean> dateStyles = new HashMap<>();

        private final Map<Integer, Column> columns = new TreeMap<>();
        private final Set<String> seen = new LinkedHashSet<>();
        private final List<RawRow> rows = new ArrayList<>();
        private boolean headerDone;

        private int rowIndex = -1;
        private Map<Integer, RawCell> rowCells = new HashMap<>();
        private int columnIndex = -1;
        private @Nullable String cellType;
        private int cellStyle;
        private final StringBuilder value = new StringBuilder();
        private boolean hasValue;
        private boolean inValue;
        private boolean inInlineString;
        private boolean inText;
        private boolean inPhonetic;

        SheetHandler(RecordMapping<?> mapping, SharedStrings sharedStrings, @Nullable StylesTable styles,
                int maxRows) {
            this.mapping = mapping;
            this.sharedStrings = sharedStrings;
            this.styles = styles;
            this.maxRows = maxRows;
        }

        Map<Integer, Column> columns() {
            if (!headerDone) {
                throw new InvalidFileException("File không có dòng tiêu đề");
            }
            return columns;
        }

        List<RawRow> rows() {
            return rows;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
                case "row" -> {
                    String ref = attributes.getValue("r");
                    rowIndex = ref == null ? rowIndex + 1 : Integer.parseInt(ref) - 1;
                    rowCells = new HashMap<>();
                    columnIndex = -1;
                }
                case "c" -> {
                    String ref = attributes.getValue("r");
                    columnIndex = ref == null ? columnIndex + 1 : new CellReference(ref).getCol();
                    cellType = attributes.getValue("t");
                    String style = attributes.getValue("s");
                    cellStyle = style == null ? 0 : Integer.parseInt(style);
                    value.setLength(0);
                    hasValue = false;
                }
                case "v" -> inValue = true;
                case "is" -> inInlineString = true;
                case "rPh" -> inPhonetic = true;
                case "t" -> inText = inInlineString && !inPhonetic;
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inValue || inText) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (localName) {
                case "v" -> {
                    inValue = false;
                    hasValue = true;
                }
                case "t" -> {
                    if (inText) {
                        hasValue = true;
                    }
                    inText = false;
                }
                case "rPh" -> inPhonetic = false;
                case "is" -> inInlineString = false;
                case "c" -> endCell();
                case "row" -> endRow();
                default -> {
                }
            }
        }

        private void endCell() {
            if (!headerDone) {
                headerCell(rawCell());
            } else if (columns.containsKey(columnIndex)) {
                rowCells.put(columnIndex, rawCell());
            }
        }

        private RawCell rawCell() {
            if (!hasValue) {
                return RawCell.BLANK;
            }
            String text = value.toString();
            String type = cellType == null ? "n" : cellType;
            try {
                return switch (type) {
                    case "s" -> new RawCell(Kind.STRING,
                            sharedStrings.getItemAt(Integer.parseInt(text.strip())).getString(), false);
                    case "inlineStr", "str" -> new RawCell(Kind.STRING, text, false);
                    case "b" -> new RawCell(Kind.BOOLEAN, text.strip(), false);
                    case "e" -> new RawCell(Kind.ERROR, text, false);
                    case "n" -> {
                        String number = text.strip();
                        Double.parseDouble(number);
                        new BigDecimal(number);
                        yield new RawCell(Kind.NUMERIC, number, isDateStyle(cellStyle));
                    }
                    default -> RawCell.MALFORMED;
                };
            } catch (NumberFormatException | IllegalStateException | IndexOutOfBoundsException ex) {
                // A numeric cell holding text, or a shared string index that isn't in the table.
                return RawCell.MALFORMED;
            }
        }

        private boolean isDateStyle(int styleIndex) {
            if (styles == null) {
                return false;
            }
            return dateStyles.computeIfAbsent(styleIndex, index -> {
                XSSFCellStyle style = index < styles.getNumCellStyles() ? styles.getStyleAt(index) : null;
                return style != null && DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
            });
        }

        private void headerCell(RawCell cell) {
            String name = cell.kind() == Kind.STRING && cell.text() != null ? cell.text().strip() : "";
            if (name.isEmpty()) {
                if (cell.kind() != Kind.BLANK) {
                    throw new InvalidFileException("Tiêu đề cột %s phải là chữ"
                            .formatted(new CellReference(rowIndex, columnIndex).formatAsString()));
                }
                return;
            }
            RecordComponent component = mapping.byColumn().get(name.toLowerCase(Locale.ROOT));
            if (component == null) {
                throw new InvalidFileException("Cột lạ: \"%s\". Các cột hợp lệ: %s"
                        .formatted(name, String.join(", ", mapping.columnNames())));
            }
            if (!seen.add(component.getName())) {
                throw new InvalidFileException("Cột \"%s\" xuất hiện hai lần".formatted(name));
            }
            columns.put(columnIndex, new Column(columnName(component), component, mapping.indexOf(component)));
        }

        private void endRow() {
            if (!headerDone) {
                List<String> missing = mapping.requiredColumns().stream()
                        .filter(required -> columns.values().stream().noneMatch(c -> c.name().equals(required)))
                        .toList();
                if (!missing.isEmpty()) {
                    throw new InvalidFileException("Thiếu cột bắt buộc: " + String.join(", ", missing));
                }
                headerDone = true;
                return;
            }
            RawRow row = new RawRow(rowIndex + 1, rowCells);
            if (isBlank(row)) {
                return;
            }
            if (rows.size() == maxRows) {
                // Stop here: the rest of the file is never read.
                throw new RowValidationException("File có hơn %d dòng dữ liệu".formatted(maxRows), List.of());
            }
            rows.add(row);
        }
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
