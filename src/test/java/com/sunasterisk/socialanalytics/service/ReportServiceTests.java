package com.sunasterisk.socialanalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.dto.ReportFilter;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.excel.ExcelExport;
import com.sunasterisk.socialanalytics.excel.ExcelWriter;
import com.sunasterisk.socialanalytics.exception.UnprocessableException;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository.DailyStats;
import com.sunasterisk.socialanalytics.repository.ReportRepository;
import com.sunasterisk.socialanalytics.repository.ReportRepository.ReportEntry;
import com.sunasterisk.socialanalytics.service.ExchangeRateService.ExchangeRate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;

class ReportServiceTests {

    /** 2026-03-02 01:00 in Asia/Ho_Chi_Minh: the export date is the local one, not the UTC one (03-01). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T18:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));

    private final ReportRepository repository = mock(ReportRepository.class);
    private final DailyStatsRepository dailyStatsRepository = mock(DailyStatsRepository.class);
    private final ExchangeRateService exchangeRates = mock(ExchangeRateService.class);
    private final ReportService service = new ReportService(repository, dailyStatsRepository, new ExcelWriter(CLOCK),
            exchangeRates, CLOCK, mock(PlatformTransactionManager.class));

    @Test
    void filtersPublishedAtByLocalDaysBothIncluded() throws IOException {
        when(repository.countPosts(any(), any(), any())).thenReturn(1L);
        when(repository.findPage(any(), any(), any(), anyLong(), anyInt())).thenReturn(List.of(entry(1)));

        try (ExportedReport report = service.export(new ReportFilter(LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28), SocialPlatform.FACEBOOK))) {
            assertThat(report.fileName()).isEqualTo("social-report-2026-03-02.xlsx");
        }

        // [2026-02-01 00:00, 2026-03-01 00:00) in UTC+7
        Instant from = Instant.parse("2026-01-31T17:00:00Z");
        Instant before = Instant.parse("2026-02-28T17:00:00Z");
        verify(repository).countPosts(SocialPlatform.FACEBOOK, from, before);
        verify(repository).findPage(SocialPlatform.FACEBOOK, from, before, 0, ReportService.PAGE_SIZE);
    }

    @Test
    void withoutFilterPassesNullBounds() throws IOException {
        when(repository.findPage(any(), any(), any(), anyLong(), anyInt())).thenReturn(List.of());

        try (ExportedReport report = service.export(new ReportFilter(null, null, null))) {
            assertThat(rows(report)).isZero();
        }

        verify(repository).findPage(isNull(), isNull(), isNull(), eq(0L), eq(ReportService.PAGE_SIZE));
    }

    @Test
    void readsPagesAfterTheLastIdUntilAShortPage() throws IOException {
        int pageSize = ReportService.PAGE_SIZE;
        when(repository.countPosts(any(), any(), any())).thenReturn(pageSize + 2L);
        when(repository.findPage(any(), any(), any(), eq(0L), anyInt())).thenReturn(entries(1, pageSize));
        when(repository.findPage(any(), any(), any(), eq((long) pageSize), anyInt()))
                .thenReturn(entries(pageSize + 1, 2));

        try (ExportedReport report = service.export(new ReportFilter(null, null, null))) {
            assertThat(rows(report)).isEqualTo(pageSize + 2);
        }
    }

    @Test
    void stopsAfterAFullPageFollowedByAnEmptyOne() throws IOException {
        int pageSize = ReportService.PAGE_SIZE;
        when(repository.findPage(any(), any(), any(), eq(0L), anyInt())).thenReturn(entries(1, pageSize));
        when(repository.findPage(any(), any(), any(), eq((long) pageSize), anyInt())).thenReturn(List.of());

        try (ExportedReport report = service.export(new ReportFilter(null, null, null))) {
            assertThat(rows(report)).isEqualTo(pageSize);
        }
    }

    @Test
    void dailySheetHoldsTheStatisticsOfTheFilteredDays() throws IOException {
        LocalDate from = LocalDate.of(2026, 2, 1);
        LocalDate to = LocalDate.of(2026, 2, 2);
        when(dailyStatsRepository.find(from, to, SocialPlatform.TWITTER)).thenReturn(List.of(
                new DailyStats(from, SocialPlatform.TWITTER, 4, 3, 1200, 80, 40, 25050),
                new DailyStats(to, SocialPlatform.TWITTER, 4, 4, 1350, 92, 41, 25060)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportedReport report = service.export(new ReportFilter(from, to, SocialPlatform.TWITTER))) {
            report.write(out);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet daily = workbook.getSheet("Daily");
            assertThat(daily.getRow(0)).extracting(Cell::getStringCellValue).containsExactly("date", "platform",
                    "postsTracked", "postsMeasured", "likes", "shares", "comments", "followers");
            assertThat(daily.getLastRowNum()).isEqualTo(2);
            assertThat(daily.getRow(2).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(to);
            assertThat(daily.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(1350);
            assertThat(daily.getRow(2).getCell(7).getNumericCellValue()).isEqualTo(25060);
        }
    }

    @Test
    void convertsAdCostWithTodaysRateAskedBeforeTheTransactionAndListsTheRate() throws IOException {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        ReportService withRate = new ReportService(repository, dailyStatsRepository, new ExcelWriter(CLOCK),
                exchangeRates, CLOCK, transactions);
        LocalDate today = LocalDate.of(2026, 3, 2);
        when(exchangeRates.usdToVndToday())
                .thenReturn(Optional.of(new ExchangeRate("USD", "VND", today, new BigDecimal("25100"))));
        when(repository.findPage(any(), any(), any(), anyLong(), anyInt())).thenReturn(List.of(
                entry(1, new BigDecimal("12.34")), entry(2, null)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportedReport report = withRate.export(new ReportFilter(null, null, null))) {
            report.write(out);
        }

        // No database connection is held while the SOAP service answers.
        InOrder order = inOrder(exchangeRates, transactions);
        order.verify(exchangeRates).usdToVndToday();
        order.verify(transactions).getTransaction(any());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet posts = workbook.getSheet("Posts");
            int adCost = columnIndex(posts, "adCost");
            assertThat(posts.getRow(0).getCell(adCost + 1).getStringCellValue()).isEqualTo("adCostVnd");
            assertThat(posts.getRow(1).getCell(adCost).getNumericCellValue()).isEqualTo(12.34);
            // 12.34 × 25,100 = 309,734
            assertThat(posts.getRow(1).getCell(adCost + 1).getNumericCellValue()).isEqualTo(309_734);
            assertThat(posts.getRow(2).getCell(adCost)).isNull();
            assertThat(posts.getRow(2).getCell(adCost + 1)).isNull();

            Sheet rates = workbook.getSheet("ExchangeRates");
            assertThat(rates.getRow(0)).extracting(Cell::getStringCellValue)
                    .containsExactly("date", "from", "to", "rate", "note");
            assertThat(rates.getRow(1).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(today);
            assertThat(rates.getRow(1).getCell(1).getStringCellValue()).isEqualTo("USD");
            assertThat(rates.getRow(1).getCell(2).getStringCellValue()).isEqualTo("VND");
            assertThat(rates.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(25_100);
            assertThat(rates.getRow(1).getCell(4)).isNull();
        }
    }

    @Test
    void withoutARateTheExportStillWorksWithEmptyVndAndANote() throws IOException {
        when(exchangeRates.usdToVndToday()).thenReturn(Optional.empty());
        when(repository.findPage(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(entry(1, new BigDecimal("12.34"))));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportedReport report = service.export(new ReportFilter(null, null, null))) {
            report.write(out);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet posts = workbook.getSheet("Posts");
            int adCost = columnIndex(posts, "adCost");
            assertThat(posts.getRow(1).getCell(adCost).getNumericCellValue()).isEqualTo(12.34);
            assertThat(posts.getRow(1).getCell(adCost + 1)).isNull();
            Row rate = workbook.getSheet("ExchangeRates").getRow(1);
            assertThat(rate.getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(rate.getCell(3)).isNull();
            assertThat(rate.getCell(4).getStringCellValue())
                    .isEqualTo("Không lấy được tỷ giá: cột adCostVnd để trống");
        }
    }

    @Test
    void refusesMoreRowsThanTheLimitBeforeReadingThem() {
        when(repository.countPosts(any(), any(), any())).thenReturn(ReportService.MAX_ROWS + 1L);

        assertThatThrownBy(() -> service.export(new ReportFilter(null, null, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageStartingWith("Có 100001 bài viết khớp bộ lọc, tối đa 100000 bài mỗi file");
        verify(repository, never()).findPage(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void closesTheWorkbookWhenReadingFails() throws IOException {
        ExcelWriter writer = mock(ExcelWriter.class);
        ExcelExport file = mock(ExcelExport.class);
        when(writer.newWorkbook()).thenReturn(file);
        doThrow(new IOException("disk full")).when(file).close();
        when(repository.findPage(any(), any(), any(), anyLong(), anyInt()))
                .thenThrow(new QueryTimeoutException("timeout"));
        ReportService failing = new ReportService(repository, dailyStatsRepository, writer, exchangeRates, CLOCK,
                mock(PlatformTransactionManager.class));

        assertThatThrownBy(() -> failing.export(new ReportFilter(null, null, null)))
                .isInstanceOf(QueryTimeoutException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).singleElement()
                        .extracting(Throwable::getMessage).isEqualTo("disk full"));
        verify(file).close();
    }

    private static ReportEntry entry(long id) {
        return entry(id, null);
    }

    private static ReportEntry entry(long id, @Nullable BigDecimal adCost) {
        return new ReportEntry(id, SocialPlatform.FACEBOOK, "ext-" + id, "page", null, null, null, adCost, null,
                null, null, null, null);
    }

    private static int columnIndex(Sheet sheet, String name) {
        for (Cell cell : sheet.getRow(0)) {
            if (cell.getStringCellValue().equals(name)) {
                return cell.getColumnIndex();
            }
        }
        throw new AssertionError("No column " + name);
    }

    private static List<ReportEntry> entries(long firstId, int count) {
        return LongStream.range(firstId, firstId + count).mapToObj(ReportServiceTests::entry).toList();
    }

    private static int rows(ExportedReport report) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.write(out);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Posts");
            return sheet.getLastRowNum();
        }
    }
}
