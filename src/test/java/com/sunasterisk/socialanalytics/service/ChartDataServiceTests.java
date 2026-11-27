package com.sunasterisk.socialanalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.dto.ChartDataFilter;
import com.sunasterisk.socialanalytics.dto.ChartDataResponse;
import com.sunasterisk.socialanalytics.dto.ChartDataResponse.Series;
import com.sunasterisk.socialanalytics.entity.MetricUpdateRun;
import com.sunasterisk.socialanalytics.entity.RunTrigger;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository.DailyStats;
import com.sunasterisk.socialanalytics.repository.MetricUpdateRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChartDataServiceTests {

    /** 2026-11-20 10:00 in Vietnam (still 11-20 in UTC too). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final LocalDate DAY_1 = LocalDate.of(2026, 11, 14);

    private final DailyStatsRepository repository = mock(DailyStatsRepository.class);
    private final MetricUpdateRunRepository runs = mock(MetricUpdateRunRepository.class);
    private final ChartDataService service = new ChartDataService(repository, runs, CLOCK);

    @BeforeEach
    void noData() {
        when(repository.find(any(), any(), any())).thenReturn(List.of());
        when(repository.findLastBefore(any(), any())).thenReturn(Optional.empty());
        when(runs.findFirstByStatusInOrderByFinishedAtDescIdDesc(any())).thenReturn(Optional.empty());
    }

    @Test
    void defaultsToTheLastSevenDaysUpToTodayForBothPlatforms() {
        ChartDataResponse response = service.chartData(new ChartDataFilter(null, null, null));

        assertThat(response.from()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(response.to()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(response.dates()).hasSize(7).startsWith(response.from()).endsWith(response.to());
        assertThat(response.series()).extracting(Series::platform)
                .containsExactly(SocialPlatform.FACEBOOK, SocialPlatform.TWITTER);
        // Nothing measured yet: zeros, not gaps.
        assertThat(response.series().getFirst().likes()).containsOnly(0L).hasSize(7);
        assertThat(response.lastUpdatedAt()).isNull();
    }

    @Test
    void aDayWithoutARowKeepsTheLastValuesWithNothingMeasured() {
        // Q12: Facebook 150 likes on day 1, nothing recomputed on day 2 (app off), 190 on day 3.
        when(repository.find(DAY_1, DAY_1.plusDays(2), SocialPlatform.FACEBOOK)).thenReturn(List.of(
                stats(DAY_1, 2, 2, 150, 1500), stats(DAY_1.plusDays(2), 2, 2, 190, 1530)));

        Series facebook = service.chartData(new ChartDataFilter(DAY_1, DAY_1.plusDays(2), SocialPlatform.FACEBOOK))
                .series().getFirst();

        assertThat(facebook.likes()).containsExactly(150L, 150L, 190L);
        assertThat(facebook.followers()).containsExactly(1500L, 1500L, 1530L);
        assertThat(facebook.postsTracked()).containsExactly(2, 2, 2);
        assertThat(facebook.postsMeasured()).containsExactly(2, 0, 2);
    }

    @Test
    void aRangeStartingInAGapStartsFromTheLastRowBeforeIt() {
        when(repository.findLastBefore(DAY_1, SocialPlatform.TWITTER))
                .thenReturn(Optional.of(twitter(stats(DAY_1.minusDays(5), 4, 4, 80, 900))));
        when(repository.find(DAY_1, DAY_1.plusDays(1), SocialPlatform.TWITTER))
                .thenReturn(List.of(twitter(stats(DAY_1.plusDays(1), 4, 3, 95, 910))));

        Series twitter = service.chartData(new ChartDataFilter(DAY_1, DAY_1.plusDays(1), SocialPlatform.TWITTER))
                .series().getFirst();

        assertThat(twitter.likes()).containsExactly(80L, 95L);
        assertThat(twitter.postsMeasured()).containsExactly(0, 3);
    }

    @Test
    void onlyFromGivenShowsSevenDaysFromIt() {
        ChartDataResponse response = service.chartData(new ChartDataFilter(DAY_1, null, null));

        assertThat(response.to()).isEqualTo(DAY_1.plusDays(6));
    }

    @Test
    void lastUpdatedAtIsTheEndOfTheLastRunThatUpdatedTheMetrics() {
        MetricUpdateRun run = new MetricUpdateRun(RunTrigger.SCHEDULED, Instant.parse("2026-11-20T02:00:00Z"));
        run.finish(Instant.parse("2026-11-20T02:00:05Z"), 1, 0, 1, null);
        when(runs.findFirstByStatusInOrderByFinishedAtDescIdDesc(any())).thenReturn(Optional.of(run));

        assertThat(service.chartData(new ChartDataFilter(null, null, null)).lastUpdatedAt())
                .isEqualTo(Instant.parse("2026-11-20T02:00:05Z"));
    }

    private static DailyStats twitter(DailyStats stats) {
        return new DailyStats(stats.statDate(), SocialPlatform.TWITTER, stats.postsTracked(), stats.postsMeasured(),
                stats.likes(), stats.shares(), stats.comments(), stats.followers());
    }

    private static DailyStats stats(LocalDate day, int tracked, int measured, long likes, long followers) {
        return new DailyStats(day, SocialPlatform.FACEBOOK, tracked, measured, likes, 1, 2, followers);
    }
}
