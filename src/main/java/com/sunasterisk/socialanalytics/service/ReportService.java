package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.dto.DailyReportRow;
import com.sunasterisk.socialanalytics.dto.ExchangeRateRow;
import com.sunasterisk.socialanalytics.dto.PostReportRow;
import com.sunasterisk.socialanalytics.dto.ReportFilter;
import com.sunasterisk.socialanalytics.excel.ExcelExport;
import com.sunasterisk.socialanalytics.excel.ExcelWriter;
import com.sunasterisk.socialanalytics.excel.ExcelWriter.SheetWriter;
import com.sunasterisk.socialanalytics.exception.UnprocessableException;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository;
import com.sunasterisk.socialanalytics.repository.ReportRepository;
import com.sunasterisk.socialanalytics.repository.ReportRepository.ReportEntry;
import com.sunasterisk.socialanalytics.service.ExchangeRateService.ExchangeRate;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the Excel report ({@code docs/cau-hoi-lam-ro.md} Q11):
 *
 * <ul>
 *   <li>"Posts": every post matching the filter with its latest snapshot, by id.</li>
 *   <li>"Daily": the statistics of each day of {@code [from, to]} (all days when not given) and platform, as
 *       recomputed by the JMS listener ({@code daily_platform_stats}).</li>
 *   <li>"ExchangeRates": the USD → VND rate of the SOAP service used for {@code adCostVnd} (Q7), or a note when
 *       there is none.</li>
 * </ul>
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
    private final DailyStatsRepository dailyStatsRepository;
    private final ExcelWriter excelWriter;
    private final ExchangeRateService exchangeRateService;
    private final Clock clock;
    private final TransactionTemplate readOnly;

    public ReportService(ReportRepository reportRepository, DailyStatsRepository dailyStatsRepository,
            ExcelWriter excelWriter, ExchangeRateService exchangeRateService, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.reportRepository = reportRepository;
        this.dailyStatsRepository = dailyStatsRepository;
        this.excelWriter = excelWriter;
        this.exchangeRateService = exchangeRateService;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Today's USD → VND rate is asked first, outside the transaction (no database connection held while the SOAP
     * service answers); without it the export still works, with {@code adCostVnd} empty and a note.
     */
    public ExportedReport export(ReportFilter filter) {
        ExchangeRate rate = exchangeRateService.usdToVndToday().orElse(null);
        ExportedReport report = readOnly.execute(status -> build(filter, rate));
        if (report == null) {
            throw new IllegalStateException("Report not built");
        }
        return report;
    }

    private ExportedReport build(ReportFilter filter, @Nullable ExchangeRate rate) {
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
                page.forEach(entry -> posts.append(toRow(entry, rate)));
                if (!page.isEmpty()) {
                    afterId = page.getLast().postId();
                }
            } while (page.size() == PAGE_SIZE);
            SheetWriter<DailyReportRow> daily = excelWriter.addSheet(file, "Daily", DailyReportRow.class);
            dailyStatsRepository.find(filter.from(), filter.to(), filter.platform())
                    .forEach(stats -> daily.append(DailyReportRow.from(stats)));
            SheetWriter<ExchangeRateRow> rates = excelWriter.addSheet(file, "ExchangeRates", ExchangeRateRow.class);
            LocalDate today = LocalDate.now(clock);
            rates.append(rate == null
                    ? new ExchangeRateRow(today, ExchangeRateService.USD, ExchangeRateService.VND, null,
                            "Không lấy được tỷ giá: cột adCostVnd để trống")
                    : new ExchangeRateRow(rate.date(), rate.from(), rate.to(), rate.rate(), null));
            log.info("Exported {} posts and {} daily rows (filter {})", posts.rowCount(), daily.rowCount(), filter);
            return new ExportedReport("social-report-%s.xlsx".formatted(LocalDate.now(clock)), file);
        } catch (RuntimeException ex) {
            closeQuietly(file, ex);
            throw ex;
        }
    }

    private static PostReportRow toRow(ReportEntry entry, @Nullable ExchangeRate rate) {
        BigDecimal adCostVnd = rate == null || entry.adCost() == null ? null : rate.convert(entry.adCost());
        return new PostReportRow(entry.platform(), entry.externalId(), entry.accountName(), entry.url(),
                entry.content(), entry.publishedAt(), entry.adCost(), adCostVnd, entry.likes(), entry.shares(),
                entry.comments(), entry.followers(), entry.capturedAt());
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
