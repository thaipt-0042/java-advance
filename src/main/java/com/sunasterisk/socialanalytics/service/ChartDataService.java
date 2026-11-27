package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.dto.ChartDataFilter;
import com.sunasterisk.socialanalytics.dto.ChartDataResponse;
import com.sunasterisk.socialanalytics.dto.ChartDataResponse.Series;
import com.sunasterisk.socialanalytics.entity.MetricUpdateRun;
import com.sunasterisk.socialanalytics.entity.RunStatus;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository.DailyStats;
import com.sunasterisk.socialanalytics.repository.MetricUpdateRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the chart data from {@code daily_platform_stats} ({@code docs/cau-hoi-lam-ro.md} Q12). Every day of the
 * range has a value: a day without a row (nobody recomputed it, e.g. the app was off all day) had no new snapshot,
 * so it keeps the last row before it, with {@code postsMeasured} = 0; before a platform's first row, every value
 * is 0 (nothing measured yet). The last row before {@code from} is read too, so a range starting in a gap is right.
 */
@Service
@Transactional(readOnly = true)
public class ChartDataService {

    private final DailyStatsRepository dailyStatsRepository;
    private final MetricUpdateRunRepository runRepository;
    private final Clock clock;

    public ChartDataService(DailyStatsRepository dailyStatsRepository, MetricUpdateRunRepository runRepository,
            Clock clock) {
        this.dailyStatsRepository = dailyStatsRepository;
        this.runRepository = runRepository;
        this.clock = clock;
    }

    public ChartDataResponse chartData(ChartDataFilter filter) {
        LocalDate to = filter.to() != null ? filter.to()
                : filter.from() != null ? filter.from().plusDays(ChartDataFilter.DEFAULT_DAYS - 1L)
                : LocalDate.now(clock);
        LocalDate from = filter.from() != null ? filter.from() : to.minusDays(ChartDataFilter.DEFAULT_DAYS - 1L);
        List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();
        List<SocialPlatform> platforms = filter.platform() != null ? List.of(filter.platform())
                : Arrays.asList(SocialPlatform.values());
        Map<SocialPlatform, Map<LocalDate, DailyStats>> rows = dailyStatsRepository
                .find(from, to, filter.platform()).stream()
                .collect(Collectors.groupingBy(DailyStats::platform,
                        Collectors.toMap(DailyStats::statDate, Function.identity())));
        List<Series> series = platforms.stream()
                .map(platform -> series(platform, dates, rows.getOrDefault(platform, Map.of()),
                        dailyStatsRepository.findLastBefore(from, platform).orElse(null)))
                .toList();
        return new ChartDataResponse(from, to, dates, series, lastUpdatedAt());
    }

    private static Series series(SocialPlatform platform, List<LocalDate> dates, Map<LocalDate, DailyStats> rows,
            @Nullable DailyStats before) {
        List<Integer> tracked = new ArrayList<>();
        List<Integer> measured = new ArrayList<>();
        List<Long> likes = new ArrayList<>();
        List<Long> shares = new ArrayList<>();
        List<Long> comments = new ArrayList<>();
        List<Long> followers = new ArrayList<>();
        DailyStats last = before;
        for (LocalDate day : dates) {
            DailyStats row = rows.get(day);
            if (row != null) {
                last = row;
            }
            tracked.add(last == null ? 0 : last.postsTracked());
            // Carried days measured nothing.
            measured.add(row == null ? 0 : row.postsMeasured());
            likes.add(last == null ? 0 : last.likes());
            shares.add(last == null ? 0 : last.shares());
            comments.add(last == null ? 0 : last.comments());
            followers.add(last == null ? 0 : last.followers());
        }
        return new Series(platform, tracked, measured, likes, shares, comments, followers);
    }

    private @Nullable Instant lastUpdatedAt() {
        return runRepository.findFirstByStatusInOrderByFinishedAtDescIdDesc(
                        List.of(RunStatus.SUCCEEDED, RunStatus.PARTIAL))
                .map(MetricUpdateRun::getFinishedAt)
                .orElse(null);
    }
}
