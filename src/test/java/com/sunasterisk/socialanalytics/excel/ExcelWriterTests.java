package com.sunasterisk.socialanalytics.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelWriterTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T03:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));

    private final ExcelWriter writer = new ExcelWriter(CLOCK);

    record Sample(
            @ExcelColumn(name = "name") String name,
            @ExcelColumn(name = "count") Long count,
            @ExcelColumn(name = "rank") Integer rank,
            @ExcelColumn(name = "at") Instant at,
            @ExcelColumn(name = "day") LocalDate day,
            @ExcelColumn(name = "platform") SocialPlatform platform) {
    }

    @Test
    void writesBoldFrozenHeaderThenOneRowPerRecordInComponentOrder() throws IOException {
        byte[] file = write(new Sample("first", 12L, 3, Instant.parse("2026-03-01T03:00:00Z"),
                LocalDate.of(2026, 3, 2), SocialPlatform.TWITTER), new Sample("second", null, null, null, null, null));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheet("Samples");
            Row header = sheet.getRow(0);
            assertThat(header).extracting(Cell::getStringCellValue)
                    .containsExactly("name", "count", "rank", "at", "day", "platform");
            assertThat(workbook.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();

            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("first");
            assertThat(first.getCell(1).getNumericCellValue()).isEqualTo(12);
            assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(3);
            // 03:00 UTC is 10:00 in Asia/Ho_Chi_Minh, written as a date cell.
            assertThat(DateUtil.isCellDateFormatted(first.getCell(3))).isTrue();
            assertThat(first.getCell(3).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 0));
            assertThat(first.getCell(3).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm:ss");
            assertThat(first.getCell(4).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(first.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");
            assertThat(first.getCell(5).getStringCellValue()).isEqualTo("TWITTER");

            Row second = sheet.getRow(2);
            assertThat(second.getCell(0).getStringCellValue()).isEqualTo("second");
            assertThat(Stream.of(1, 2, 3, 4, 5).map(second::getCell)).containsOnlyNulls();
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void writesFormulaLikeTextAsTextNotAsFormula() throws IOException {
        byte[] file = write(new Sample("=HYPERLINK(\"http://evil.example\",\"click\")", null, null, null, null,
                null));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Cell cell = workbook.getSheet("Samples").getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo("=HYPERLINK(\"http://evil.example\",\"click\")");
        }
    }

    @Test
    void quotePrefixesTextThatExcelWouldTakeAsAFormulaOnceEdited() throws IOException {
        // Without the prefix, editing the cell and pressing Enter turns these into formulas.
        String[] formulaLike = {"=1+1", "+1", "-1", "@abc", "\tx", "\rx", "\nx"};
        String[] plain = {"abc", "a=b", " =1", ""};
        byte[] file = write(Stream.concat(Stream.of(formulaLike), Stream.of(plain))
                .map(text -> new Sample(text, null, null, null, null, null))
                .toArray(Sample[]::new));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheet("Samples");
            for (int i = 0; i < formulaLike.length + plain.length; i++) {
                Cell cell = sheet.getRow(i + 1).getCell(0);
                boolean quoted = i < formulaLike.length;
                String text = quoted ? formulaLike[i] : plain[i - formulaLike.length];
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                assertThat(cell.getStringCellValue()).isEqualTo(text);
                assertThat(cell.getCellStyle().getQuotePrefixed()).as(cell.getStringCellValue()).isEqualTo(quoted);
            }
        }
    }

    record Imported(
            @ExcelColumn(name = "name", required = true) String name,
            @ExcelColumn(name = "at") Instant at,
            @ExcelColumn(name = "cost") BigDecimal cost) {
    }

    record Exported(
            @ExcelColumn(name = "name") String name,
            @ExcelColumn(name = "at") Instant at,
            @ExcelColumn(name = "cost") BigDecimal cost) {
    }

    @Test
    void exportedDateAndAmountReadBackAsTheSameValues() throws IOException {
        Instant at = Instant.parse("2026-11-20T17:30:15.123Z");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelExport export = writer.newWorkbook()) {
            writer.addSheet(export, "Rows", Exported.class).append(new Exported("a", at, new BigDecimal("1234.56")));
            export.write(out);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Cell cost = workbook.getSheet("Rows").getRow(1).getCell(2);
            // A number cell (sums work in Excel), shown with thousands separators and two decimals
            assertThat(cost.getNumericCellValue()).isEqualTo(1234.56);
            assertThat(cost.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        }

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            ExcelSheet<Imported> sheet = new ExcelReader(factory.getValidator(), CLOCK)
                    .read(new ByteArrayInputStream(out.toByteArray()), Imported.class, 10);
            assertThat(sheet.errors()).isEmpty();
            assertThat(sheet.rows().getFirst().value().at()).isEqualTo(at);
            assertThat(sheet.rows().getFirst().value().cost()).isEqualByComparingTo("1234.56");
        }
    }

    @Test
    void keepsOnlyTheLastRowsInMemoryAndCountsRows() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelExport export = writer.newWorkbook()) {
            ExcelWriter.SheetWriter<Exported> sheet = writer.addSheet(export, "Rows", Exported.class);
            for (int i = 0; i < 1_000; i++) {
                sheet.append(new Exported("row " + i, null, null));
            }
            assertThat(sheet.rowCount()).isEqualTo(1_000);
            // Older rows were flushed to the temporary file.
            assertThat((Object) export.workbook().getSheet("Rows").getRow(1)).isNull();
            export.write(out);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Rows");
            assertThat(sheet.getLastRowNum()).isEqualTo(1_000);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("row 0");
            assertThat(sheet.getRow(1_000).getCell(0).getStringCellValue()).isEqualTo("row 999");
        }
    }

    @Test
    void closeDeletesTheTemporaryFiles() throws IOException {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "poifiles");
        ExcelExport export = writer.newWorkbook();
        ExcelWriter.SheetWriter<Exported> sheet = writer.addSheet(export, "Rows", Exported.class);
        for (int i = 0; i < 500; i++) {
            sheet.append(new Exported("row " + i, null, null));
        }
        long whileOpen = sheetTempFiles(tempDir);
        assertThat(whileOpen).isPositive();

        export.close();

        assertThat(sheetTempFiles(tempDir)).isEqualTo(whileOpen - 1);
    }

    record NoAnnotation(@ExcelColumn(name = "a") String a, String b) {
    }

    record Repeated(@ExcelColumn(name = "A") String a, @ExcelColumn(name = "a") String b) {
    }

    record Unsupported(@ExcelColumn(name = "a") Double a) {
    }

    @Test
    void rejectsRecordsItCannotWrite() throws IOException {
        try (ExcelExport export = writer.newWorkbook()) {
            assertThatThrownBy(() -> writer.addSheet(export, "x", NoAnnotation.class))
                    .isInstanceOf(IllegalStateException.class).hasMessage("NoAnnotation.b has no @ExcelColumn");
            assertThatThrownBy(() -> writer.addSheet(export, "y", Repeated.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Duplicate @ExcelColumn \"a\" in Repeated");
            assertThatThrownBy(() -> writer.addSheet(export, "z", Unsupported.class))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Unsupported.a: unsupported type Double");
        }
    }

    private byte[] write(Sample... rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelExport export = writer.newWorkbook()) {
            ExcelWriter.SheetWriter<Sample> sheet = writer.addSheet(export, "Samples", Sample.class);
            for (Sample row : rows) {
                sheet.append(row);
            }
            export.write(out);
        }
        return out.toByteArray();
    }

    /** Temporary sheet files of streaming workbooks (other tests may create some concurrently: compare counts). */
    private static long sheetTempFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().startsWith("poi-sxssf-sheet")).count();
        }
    }
}
