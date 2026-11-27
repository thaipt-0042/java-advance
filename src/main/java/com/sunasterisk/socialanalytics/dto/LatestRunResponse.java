package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.MetricUpdateRun;
import com.sunasterisk.socialanalytics.entity.RunStatus;
import com.sunasterisk.socialanalytics.entity.RunTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The most recent run of the metrics job (every field null before the first run), and when the metrics were last
 * updated: the end of the most recent run that updated at least one account (SUCCEEDED or PARTIAL).
 */
public record LatestRunResponse(
        @Nullable Long runId,
        @Nullable RunStatus status,
        @Nullable RunTrigger triggerType,
        @Nullable Instant startedAt,
        @Schema(description = "null khi đang chạy") @Nullable Instant finishedAt,
        @Nullable Integer accountsTotal,
        @Nullable Integer accountsFailed,
        @Nullable Integer snapshotsCreated,
        @Nullable String errorMessage,
        @Schema(description = "Thời điểm số liệu được cập nhật lần cuối (lần chạy SUCCEEDED / PARTIAL gần nhất)")
        @Nullable Instant lastUpdatedAt) {

    public static LatestRunResponse of(@Nullable MetricUpdateRun run, @Nullable Instant lastUpdatedAt) {
        if (run == null) {
            return new LatestRunResponse(null, null, null, null, null, null, null, null, null, lastUpdatedAt);
        }
        return new LatestRunResponse(run.getId(), run.getStatus(), run.getTriggerType(), run.getStartedAt(),
                run.getFinishedAt(), run.getAccountsTotal(), run.getAccountsFailed(), run.getSnapshotsCreated(),
                run.getErrorMessage(), lastUpdatedAt);
    }
}
