package com.sunasterisk.socialanalytics.excel;

import static com.sunasterisk.socialanalytics.support.Xlsx.POST_HEADER;
import static com.sunasterisk.socialanalytics.support.Xlsx.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.sunasterisk.socialanalytics.dto.PostImportRow;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.exception.InvalidFileException;
import com.sunasterisk.socialanalytics.exception.RowError;
import com.sunasterisk.socialanalytics.exception.RowValidationException;
import com.sunasterisk.socialanalytics.support.Xlsx;
import com.sunasterisk.socialanalytics.support.Xlsx.Formula;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ExcelReaderTests {

    private static ValidatorFactory validatorFactory;
    private static ExcelReader reader;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        reader = new ExcelReader(validatorFactory.getValidator(),
                Clock.fixed(Instant.parse("2026-03-01T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void mapsColumnsByHeaderNameInAnyOrderIgnoringCaseAndSpaces() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(
                row(" ACCOUNTNAME ", "followers", "externalId", "Platform", "likes", "comments", "shares", "content"),
                row("  page  ", 100, "p-1", "TWITTER", 10, 3, 2, "   ")));

        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.totalRows()).isEqualTo(1);
        assertThat(sheet.columns()).containsExactlyInAnyOrder("accountName", "followers", "externalId", "platform",
                "likes", "comments", "shares", "content");
        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNumber()).isEqualTo(2);
            // Text is stripped and an empty cell is null.
            assertThat(row.value()).isEqualTo(new PostImportRow(SocialPlatform.TWITTER, "p-1", "page", null, null,
                    null, 10L, 2L, 3L, 100L));
        });
    }

    @Test
    void readsNumericExternalIdAsPlainDigits() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", 1234567890, "page"),
                row("FACEBOOK", 999999999999999L, "page")));

        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).extracting(row -> row.value().externalId())
                .containsExactly("1234567890", "999999999999999");
    }

    @Test
    void rejectsNumbersExcelHasAlreadyRounded() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", 1234567890123456789L, "page")));

        assertThat(sheet.rows()).isEmpty();
        assertThat(sheet.errors()).containsExactly(new RowError(2, "externalId",
                "Số từ 16 chữ số trở lên đã bị Excel làm tròn, hãy nhập ô này dạng text"));
    }

    @Test
    void readsCountersFromNumberAndTextCellsButNotDecimals() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "page", null, null, null, "12", " 7 ", 3.0, 100),
                row("FACEBOOK", "b", "page", null, null, null, 1.5, "x", 1, 1)));

        assertThat(sheet.rows()).singleElement()
                .satisfies(row -> assertThat(row.value()).extracting(PostImportRow::likes, PostImportRow::shares,
                        PostImportRow::comments, PostImportRow::followers).containsExactly(12L, 7L, 3L, 100L));
        assertThat(sheet.errors()).containsExactly(
                new RowError(3, "likes", "Phải là số nguyên"),
                new RowError(3, "shares", "Phải là số nguyên"));
    }

    @Test
    void readsDateCellsInApplicationZoneAndTextWithOrWithoutOffset() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "page", null, null, LocalDateTime.of(2026, 3, 1, 10, 30)),
                row("FACEBOOK", "b", "page", null, null, "2026-03-01T03:30:00Z"),
                row("FACEBOOK", "c", "page", null, null, "2026-03-01T10:30:00+07:00"),
                row("FACEBOOK", "d", "page", null, null, "2026-03-01 10:30"),
                row("FACEBOOK", "e", "page", null, null, "01/03/2026"),
                row("FACEBOOK", "f", "page", null, null, 46000)));

        // 10:30 in Asia/Ho_Chi_Minh is 03:30 UTC
        assertThat(sheet.rows()).extracting(row -> row.value().publishedAt())
                .containsOnly(Instant.parse("2026-03-01T03:30:00Z"))
                .hasSize(4);
        assertThat(sheet.errors()).extracting(RowError::row, RowError::column)
                .containsExactly(tuple(6, "publishedAt"),
                        tuple(7, "publishedAt"));
    }

    @Test
    void platformMustMatchExactly() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("facebook", "a", "page"),
                row("INSTAGRAM", "b", "page")));

        assertThat(sheet.rows()).isEmpty();
        assertThat(sheet.errors()).containsExactly(
                new RowError(2, "platform", "Phải là một trong: FACEBOOK, TWITTER"),
                new RowError(3, "platform", "Phải là một trong: FACEBOOK, TWITTER"));
    }

    @Test
    void usesCachedValueOfFormulaCells() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", new Formula("\"id-\"&(40+2)"), "page", null, null, null,
                        new Formula("2*5"), 0, 0, 0)));

        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.value().externalId()).isEqualTo("id-42");
            assertThat(row.value().likes()).isEqualTo(10L);
        });
    }

    @Test
    void cellsOfAnUnexpectedKindGiveRowErrors() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "page", null, true, null, " ", null, "", null),
                row("FACEBOOK", "b", "page", null, null, true, new Formula("1/0"), true, 0, 0),
                row(42, "c", "page")));

        // A boolean is text "true"; empty text in the metric columns is "no metrics".
        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.value().content()).isEqualTo("true");
            assertThat(row.value().hasMetrics()).isFalse();
        });
        assertThat(sheet.errors()).containsExactly(
                new RowError(3, "publishedAt", "Phải là ngày giờ (ô dạng Date, hoặc text như 2026-03-01 10:00)"),
                new RowError(3, "likes", "Ô bị lỗi công thức"),
                new RowError(3, "shares", "Phải là số nguyên"),
                new RowError(4, "platform", "Phải là một trong: FACEBOOK, TWITTER"));
    }

    @Test
    void numericCellsHoldingTextGiveRowErrors() {
        // Written by another tool or edited by hand: <c r="B2"><v>abc</v></c> is a numeric cell whose value is text.
        byte[] file = Xlsx.of(POST_HEADER,
                row("FACEBOOK", 111, "page", null, null, LocalDateTime.of(2026, 3, 1, 10, 0), 7, 2, 3, 100),
                row("FACEBOOK", "ok", "page"));
        for (String cell : new String[] {"B2", "F2", "G2"}) {
            file = Xlsx.withRawValue(file, cell, "abc");
        }

        ExcelSheet<PostImportRow> sheet = read(file);

        assertThat(sheet.errors()).containsExactly(
                new RowError(2, "externalId", "Không đọc được giá trị của ô"),
                new RowError(2, "publishedAt", "Không đọc được giá trị của ô"),
                new RowError(2, "likes", "Không đọc được giá trị của ô"));
        assertThat(sheet.rows()).extracting(row -> row.value().externalId()).containsExactly("ok");
    }

    @Test
    void readsDateCellsWithBuiltInFormatsAndThe1904DateSystem() throws IOException {
        LocalDateTime tenAm = LocalDateTime.of(2026, 3, 1, 10, 0);
        Instant expected = Instant.parse("2026-03-01T03:00:00Z");
        // Built-in formats 14 (m/d/yy) and 22 (m/d/yy h:mm) have no format string in styles.xml.
        for (short format : new short[] {14, 22}) {
            ExcelSheet<PostImportRow> sheet = read(dateFile(tenAm, format, false));
            assertThat(sheet.errors()).isEmpty();
            Instant midnight = expected.minusSeconds(10 * 3600);
            assertThat(sheet.rows()).singleElement().extracting(row -> row.value().publishedAt())
                    .isEqualTo(format == 14 ? midnight.plusSeconds(10 * 3600) : expected);
        }
        // Old Mac workbooks count days from 1904: the same serial number is another date.
        ExcelSheet<PostImportRow> sheet = read(dateFile(tenAm, (short) 22, true));
        assertThat(sheet.rows()).singleElement().extracting(row -> row.value().publishedAt()).isEqualTo(expected);
        // A negative serial number in a date cell is no date
        assertThat(read(Xlsx.withRawValue(dateFile(tenAm, (short) 22, false), "F2", "-1")).errors())
                .containsExactly(new RowError(2, "publishedAt",
                        "Phải là ngày giờ (ô dạng Date, hoặc text như 2026-03-01 10:00)"));
    }

    @Test
    void readsCellsWithoutReferenceAttributes() {
        // The r attribute of <row> and <c> is optional: position then follows the previous row / cell, so these
        // rows have no gap (an empty text cell keeps its place).
        byte[] file = Xlsx.editSheetXml(Xlsx.of(POST_HEADER,
                        row("FACEBOOK", "a", "page"),
                        row("FACEBOOK", "b", "page", "", "", "yesterday")),
                xml -> xml.replaceAll(" r=\"[A-Z]*[0-9]+\"", ""));

        ExcelSheet<PostImportRow> sheet = read(file);

        assertThat(sheet.rows()).extracting(row -> row.value().externalId()).containsExactly("a");
        assertThat(sheet.errors()).containsExactly(new RowError(3, "publishedAt",
                "Ngày giờ không đúng định dạng (ví dụ 2026-03-01 10:00 hoặc 2026-03-01T03:00:00Z)"));
    }

    @Test
    void readsInlineStringsAndStyledEmptyCellsAndRejectsUnknownCellContent() {
        byte[] file = Xlsx.of(POST_HEADER, row("FACEBOOK", "a", "page"), row("FACEBOOK", "b", "page"));
        // B2 as an inline string in two formatted runs
        file = Xlsx.editSheetXml(file, xml -> xml.replaceFirst("<c r=\"B2\"[^>]*>.*?</c>",
                "<c r=\"B2\" t=\"inlineStr\"><is><r><t>id-</t></r><r><rPr><b/></rPr><t xml:space=\"preserve\">42 </t>"
                        + "</r></is></c>"));
        // A3 and B3 have a cell type no spreadsheet program writes
        file = Xlsx.editSheetXml(file, xml -> xml.replaceFirst("<c r=\"A3\" t=\"s\"", "<c r=\"A3\" t=\"x\"")
                .replaceFirst("<c r=\"B3\" t=\"s\"", "<c r=\"B3\" t=\"x\""));
        // Styled empty cells, as Excel writes for formatted columns: skipped in the header, null in a row
        file = Xlsx.editSheetXml(file, xml -> xml.replaceFirst("</row>", "<c r=\"K1\" s=\"0\"/></row>")
                .replaceFirst("(<row r=\"2\"[^>]*>)", "$1<c r=\"D2\" s=\"0\"/>"));

        ExcelSheet<PostImportRow> sheet = read(file);

        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.value().externalId()).isEqualTo("id-42");
            assertThat(row.value().url()).isNull();
        });
        assertThat(sheet.errors()).containsExactly(
                new RowError(3, "platform", "Không đọc được giá trị của ô"),
                new RowError(3, "externalId", "Không đọc được giá trị của ô"));
    }

    @Test
    void rejectsSheetsTheLibraryCannotParse() {
        String notXlsx = "File không phải file Excel .xlsx đọc được";
        byte[] file = Xlsx.of(POST_HEADER, row("FACEBOOK", "a", "page"));
        // A cell reference without a column, a row number that isn't one, shared strings that don't exist
        assertInvalid(Xlsx.editSheetXml(file, xml -> xml.replace("r=\"B2\"", "r=\"2B\"")), notXlsx);
        assertInvalid(Xlsx.editSheetXml(file, xml -> xml.replace("<row r=\"2\"", "<row r=\"x\"")), notXlsx);
        assertInvalid(Xlsx.withRawValue(file, "B2", "99999"), notXlsx);
        assertInvalid(Xlsx.withRawValue(file, "B2", "-1"), notXlsx);
        // A merged range written backwards, after the data
        assertInvalid(Xlsx.editSheetXml(file, xml -> xml.replace("</sheetData>",
                "</sheetData><mergeCells count=\"1\"><mergeCell ref=\"B2:A1\"/></mergeCells>")), notXlsx);
    }

    @Test
    void readsNumbersWithHugeExponentsAsDoubles() {
        // The value of a number cell is a double: Excel never writes these, and they don't build huge numbers.
        byte[] file = Xlsx.of(POST_HEADER,
                row("FACEBOOK", 111, "page", null, null, null, 7, 2, 3, 100),
                row("FACEBOOK", 222, "page", null, null, null, 7, 2, 3, 100));
        file = Xlsx.withRawValue(Xlsx.withRawValue(file, "B2", "1e-999999999"), "G2", "1e-999999999");
        file = Xlsx.withRawValue(Xlsx.withRawValue(file, "B3", "1e999999999"), "G3", "1e999999999");

        ExcelSheet<PostImportRow> sheet = read(file);

        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.value().externalId()).isEqualTo("0");
            assertThat(row.value().likes()).isZero();
        });
        // 1e999999999 is Infinity as a double
        assertThat(sheet.errors()).containsExactly(
                new RowError(3, "externalId", "Không đọc được giá trị của ô"),
                new RowError(3, "likes", "Phải là số nguyên"));
    }

    @Test
    void stopsReadingAtTheRowAfterTheLimit() {
        // Row 50 on is broken XML: with a limit of 3 it is never parsed (the library reads 10 rows ahead).
        byte[] file = Xlsx.editSheetXml(Xlsx.posts("max", 60),
                xml -> xml.replaceFirst("<row r=\"50\".*</sheetData>", "<row r=\"50\"><c r=\"A50\"><v>1</broken>"));

        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(file), PostImportRow.class, 3))
                .isInstanceOf(RowValidationException.class)
                .hasMessage("File có hơn 3 dòng dữ liệu");
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(file), PostImportRow.class, 100))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void reportsBeanValidationErrorsByColumnAndRowRulesWithoutColumn() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "  ", "ftp://example.com", "x".repeat(5001)),
                row("FACEBOOK", "b", "page", null, null, null, 1, 2)));

        assertThat(sheet.rows()).isEmpty();
        assertThat(sheet.errors()).extracting(RowError::row, RowError::column).containsExactlyInAnyOrder(
                tuple(2, "accountName"),
                tuple(2, "url"),
                tuple(2, "content"),
                tuple(3, null));
        assertThat(sheet.errors()).filteredOn(error -> error.column() == null).singleElement()
                .extracting(RowError::message)
                .isEqualTo("likes, shares, comments, followers phải có đủ cả bốn hoặc để trống cả bốn");
    }

    @Test
    void skipsBlankRowsAndCountsOnlyDataRows() {
        ExcelSheet<PostImportRow> sheet = read(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "page"),
                row(null, "  ", null),
                row(),
                row("TWITTER", "b", "page")));

        assertThat(sheet.totalRows()).isEqualTo(2);
        assertThat(sheet.rows()).extracting(ExcelRow::rowNumber).containsExactly(2, 5);
    }

    @Test
    void rejectsFileWithMissingUnknownOrRepeatedColumn() {
        assertInvalid(Xlsx.of(row("platform", "externalId"), row("FACEBOOK", "a")),
                "Thiếu cột bắt buộc: accountName");
        assertInvalid(Xlsx.of(row("platform", "externalId", "accountName", "title"), row("FACEBOOK", "a", "p", "t")),
                "Cột lạ: \"title\". Các cột hợp lệ: " + String.join(", ", POST_HEADER));
        assertInvalid(Xlsx.of(row("platform", "externalId", "accountName", "ExternalId"), row("FACEBOOK", "a", "p")),
                "Cột \"ExternalId\" xuất hiện hai lần");
        assertInvalid(Xlsx.of(row("platform", 2026, "accountName")), "Tiêu đề cột B1 phải là chữ");
    }

    @Test
    void rejectsFileWithoutHeaderOrData() {
        assertInvalid(Xlsx.of(), "File không có dòng tiêu đề");
        assertInvalid(Xlsx.of(POST_HEADER, row(null, null)), "File không có dòng dữ liệu nào");
    }

    @Test
    void rejectsWorkbookWithoutSheet() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            assertInvalid(out.toByteArray(), "File không có sheet nào");
        }
    }

    @Test
    void rejectsFilesThatAreNotReadableXlsx() {
        String notXlsx = "File không phải file Excel .xlsx đọc được";
        assertInvalid(new byte[0], notXlsx);
        assertInvalid("platform,externalId\n".getBytes(StandardCharsets.UTF_8), notXlsx);
        assertInvalid(Xlsx.xls(POST_HEADER, row("FACEBOOK", "a", "page")), notXlsx);
        // A zip that is not a workbook
        assertInvalid(zipWithEntry("hello.txt", "hi".getBytes(StandardCharsets.UTF_8)), notXlsx);
    }

    @Test
    void rejectsZipBombWithoutInflatingIt() {
        // 50MB of one character compresses to about 50KB: far beyond POI's maximum inflate ratio. It sits in an XML
        // comment, so the streaming parser keeps reading until the zip bomb check stops it.
        byte[] head = "<?xml version=\"1.0\"?><!--".getBytes(StandardCharsets.US_ASCII);
        byte[] bomb = new byte[50 * 1024 * 1024];
        Arrays.fill(bomb, (byte) 'a');
        System.arraycopy(head, 0, bomb, 0, head.length);
        byte[] file = zipWithEntry("[Content_Types].xml", bomb);
        assertThat(file.length).isLessThan(100 * 1024);

        // Rejected by POI's zip bomb check, not because the entry isn't a valid workbook part.
        assertThatThrownBy(() -> read(file)).isInstanceOf(InvalidFileException.class)
                .hasMessage("File không phải file Excel .xlsx đọc được")
                .hasStackTraceContaining("Zip bomb detected");
    }

    @Test
    void rejectsPasswordProtectedWorkbook() throws Exception {
        byte[] plain = Xlsx.of(POST_HEADER, row("FACEBOOK", "a", "page"));
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Encryptor encryptor = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
            encryptor.confirmPassword("secret");
            try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(plain));
                    OutputStream encrypted = encryptor.getDataStream(fs)) {
                pkg.save(encrypted);
            }
            fs.writeFilesystem(out);

            assertInvalid(out.toByteArray(), "File không phải file Excel .xlsx đọc được");
        }
    }

    @Test
    void rejectsMoreRowsThanAllowedWithoutRowErrors() {
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(Xlsx.posts("max", 4)), PostImportRow.class, 3))
                .isInstanceOfSatisfying(RowValidationException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("File có hơn 3 dòng dữ liệu");
                    assertThat(ex.getErrors()).isEmpty();
                });
        assertThat(reader.read(new ByteArrayInputStream(Xlsx.posts("max", 3)), PostImportRow.class, 3).rows())
                .hasSize(3);
    }

    @Test
    void rejectsRowRecordsItCannotMapBeforeReadingTheFile() {
        record Unmapped(@ExcelColumn(name = "a") String a, String b) {
        }
        record Repeated(@ExcelColumn(name = "a") String a, @ExcelColumn(name = "A") String b) {
        }
        record Unsupported(@ExcelColumn(name = "a") String a, @ExcelColumn(name = "n") Integer n) {
        }

        assertThatThrownBy(() -> reader.read(InputStream.nullInputStream(), Unmapped.class, 10))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unmapped.b has no @ExcelColumn");
        assertThatThrownBy(() -> reader.read(InputStream.nullInputStream(), Repeated.class, 10))
                .isInstanceOf(IllegalStateException.class).hasMessage("Duplicate @ExcelColumn \"A\" in Repeated");
        assertThatThrownBy(() -> reader.read(InputStream.nullInputStream(), Unsupported.class, 10))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unsupported.n: unsupported type Integer");
    }

    /** A file whose only post has publishedAt = {@code dateTime} as a date cell with a built-in format. */
    private static byte[] dateFile(LocalDateTime dateTime, short builtInFormat, boolean date1904)
            throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (date1904) {
                // A new workbook already has a <workbookPr/>: a second one is not a valid file.
                workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
            }
            Sheet sheet = workbook.createSheet("posts");
            Row header = sheet.createRow(0);
            for (int c = 0; c < POST_HEADER.size(); c++) {
                header.createCell(c).setCellValue(POST_HEADER.get(c));
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("FACEBOOK");
            row.createCell(1).setCellValue("dated");
            row.createCell(2).setCellValue("page");
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(builtInFormat);
            Cell published = row.createCell(5);
            published.setCellValue(DateUtil.getExcelDate(dateTime, date1904));
            published.setCellStyle(style);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static ExcelSheet<PostImportRow> read(byte[] file) {
        return reader.read(new ByteArrayInputStream(file), PostImportRow.class, 100);
    }

    private static void assertInvalid(byte[] file, String message) {
        assertThatThrownBy(() -> read(file)).isInstanceOf(InvalidFileException.class).hasMessage(message);
    }

    private static byte[] zipWithEntry(String name, byte[] content) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
