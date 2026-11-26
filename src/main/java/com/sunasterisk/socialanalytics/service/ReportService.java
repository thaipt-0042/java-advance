package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.dto.PostReportRow;
import com.sunasterisk.socialanalytics.dto.ReportFilter;
import com.sunasterisk.socialanalytics.excel.ExcelExport;
import com.sunasterisk.socialanalytics.excel.ExcelWriter;
import com.sunasterisk.socialanalytics.excel.ExcelWriter.SheetWriter;
import com.sunasterisk.socialanalytics.exception.UnprocessableException;
import com.sunasterisk.socialanalytics.repository.ReportRepository;
import com.sunasterisk.socialanalytics.repository.ReportRepository.ReportEntry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Excel report: sheet "Posts", every post matching the filter with its latest snapshot, by id.
 *
 * <p>The rows are read in pages inside one read-only transaction, so the count and every page see the same
 * snapshot of the database (MySQL consistent read), even if posts are added meanwhile. The file is built before
 * anything is sent: the connection is released before the (possibly slow) download starts, and an error still
 * becomes a normal error response.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** More rows would make the request too long; Excel itself stops at 1,048,575. */
    public static final int MAX_ROWS = 100_000;

    static final int PAGE_SIZE = 1_000;

    private final ReportRepository reportRepository;
    private final ExcelWriter excelWriter;
    private final Clock clock;

    public ReportService(ReportRepository reportRepository, ExcelWriter excelWriter, Clock clock) {
        this.reportRepository = reportRepository;
        this.excelWriter = excelWriter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExportedReport export(ReportFilter filter) {
        Instant publishedFrom = startOf(filter.from());
        Instant publishedBefore = filter.to() == null ? null : startOf(filter.to().plusDays(1));
        long total = reportRepository.countPosts(filter.platform(), publishedFrom, publishedBefore);
        if (total > MAX_ROWS) {
            throw new UnprocessableException(("Có %d bài viết khớp bộ lọc, tối đa %d bài mỗi file; "
                    + "hãy thu hẹp khoảng ngày hoặc lọc theo nền tảng").formatted(total, MAX_ROWS));
        }
        ExcelExport file = excelWriter.newWorkbook();
        try {
            SheetWriter<PostReportRow> posts = excelWriter.addSheet(file, "Posts", PostReportRow.class);
            long afterId = 0;
            List<ReportEntry> page;
            do {
                page = reportRepository.findPage(filter.platform(), publishedFrom, publishedBefore, afterId,
                        PAGE_SIZE);
                page.forEach(entry -> posts.append(entry.row()));
                if (!page.isEmpty()) {
                    afterId = page.getLast().postId();
                }
            } while (page.size() == PAGE_SIZE);
            log.info("Exported {} posts (filter {})", posts.rowCount(), filter);
            return new ExportedReport("social-report-%s.xlsx".formatted(LocalDate.now(clock)), file);
        } catch (RuntimeException ex) {
            closeQuietly(file, ex);
            throw ex;
        }
    }

    private @Nullable Instant startOf(@Nullable LocalDate day) {
        return day == null ? null : day.atStartOfDay(clock.getZone()).toInstant();
    }

    private static void closeQuietly(ExcelExport file, RuntimeException cause) {
        try {
            file.close();
        } catch (IOException ex) {
            cause.addSuppressed(ex);
        }
    }
}
