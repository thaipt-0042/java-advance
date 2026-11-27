package com.sunasterisk.socialanalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.LongStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class ReportServiceTests {

    /** 2026-03-02 01:00 in Asia/Ho_Chi_Minh: the export date is the local one, not the UTC one (03-01). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T18:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));

    private final ReportRepository repository = mock(ReportRepository.class);
    private final DailyStatsRepository dailyStatsRepository = mock(DailyStatsRepository.class);
    private final ReportService service = new ReportService(repository, dailyStatsRepository, new ExcelWriter(CLOCK),
            CLOCK);

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
        ReportService failing = new ReportService(repository, dailyStatsRepository, writer, CLOCK);

        assertThatThrownBy(() -> failing.export(new ReportFilter(null, null, null)))
                .isInstanceOf(QueryTimeoutException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).singleElement()
                        .extracting(Throwable::getMessage).isEqualTo("disk full"));
        verify(file).close();
    }

    private static ReportEntry entry(long id) {
        return new ReportEntry(id, SocialPlatform.FACEBOOK, "ext-" + id, "page", null, null, null, null, null,
                null, null, null);
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
