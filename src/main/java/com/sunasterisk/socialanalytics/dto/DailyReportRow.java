package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.excel.ExcelColumn;
import com.sunasterisk.socialanalytics.repository.DailyStatsRepository.DailyStats;
import java.time.LocalDate;

/**
 * One row of the "Daily" sheet of the report: the statistics of a day and platform ({@code daily_platform_stats}),
 * the state at the end of that day.
 */
public record DailyReportRow(
        @ExcelColumn(name = "date") LocalDate date,
        @ExcelColumn(name = "platform") SocialPlatform platform,
        @ExcelColumn(name = "postsTracked") Integer postsTracked,
        @ExcelColumn(name = "postsMeasured") Integer postsMeasured,
        @ExcelColumn(name = "likes") Long likes,
        @ExcelColumn(name = "shares") Long shares,
        @ExcelColumn(name = "comments") Long comments,
        @ExcelColumn(name = "followers") Long followers) {

    public static DailyReportRow from(DailyStats stats) {
        return new DailyReportRow(stats.statDate(), stats.platform(), stats.postsTracked(), stats.postsMeasured(),
                stats.likes(), stats.shares(), stats.comments(), stats.followers());
    }
}
