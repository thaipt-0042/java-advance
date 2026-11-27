package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Daily statistics for the charts ({@code docs/02-thiet-ke-api.md}): one value per day of {@code dates} in every
 * array, for each platform.
 *
 * @param lastUpdatedAt end of the last job run that updated the metrics (null if none)
 */
public record ChartDataResponse(LocalDate from, LocalDate to, List<LocalDate> dates, List<Series> series,
        @Nullable Instant lastUpdatedAt) {

    /**
     * The values of one platform. Each day is the state at the end of that day (Q12); {@code postsMeasured} below
     * {@code postsTracked} means some posts were not measured that day.
     */
    public record Series(SocialPlatform platform, List<Integer> postsTracked, List<Integer> postsMeasured,
            List<Long> likes, List<Long> shares, List<Long> comments, List<Long> followers) {
    }
}
