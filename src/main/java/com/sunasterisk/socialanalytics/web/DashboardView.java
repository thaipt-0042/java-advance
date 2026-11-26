package com.sunasterisk.socialanalytics.web;

import com.sunasterisk.socialanalytics.dto.LatestRunResponse;
import com.sunasterisk.socialanalytics.entity.RunStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;

/**
 * What the dashboard shows, already in words and in the application time zone (the template only prints).
 *
 * @param lastUpdated when the metrics were last updated, or null if never
 * @param runStatus status of the latest run in Vietnamese, or null if the job never ran
 * @param runFailed whether the latest run failed (FAILED, or PARTIAL), to highlight it
 */
public record DashboardView(
        @Nullable String lastUpdated,
        @Nullable Long runId,
        @Nullable String runStatus,
        boolean runFailed,
        boolean running,
        @Nullable String runTrigger,
        @Nullable String runStartedAt,
        @Nullable String runFinishedAt,
        @Nullable String runAccounts,
        @Nullable String runError,
        String timeZone) {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    static DashboardView of(LatestRunResponse latest, ZoneId zone) {
        RunStatus status = latest.status();
        return new DashboardView(
                format(latest.lastUpdatedAt(), zone),
                latest.runId(),
                status == null ? null : describe(status),
                status == RunStatus.FAILED || status == RunStatus.PARTIAL,
                status == RunStatus.RUNNING,
                latest.triggerType() == null ? null : switch (latest.triggerType()) {
                    case SCHEDULED -> "theo lịch";
                    case MANUAL -> "chạy tay";
                },
                format(latest.startedAt(), zone),
                format(latest.finishedAt(), zone),
                latest.accountsTotal() == null || status == RunStatus.RUNNING ? null
                        : "%d tài khoản, %d lỗi, %d snapshot".formatted(latest.accountsTotal(),
                                latest.accountsFailed(), latest.snapshotsCreated()),
                latest.errorMessage(),
                zone.getId());
    }

    private static String describe(RunStatus status) {
        return switch (status) {
            case RUNNING -> "Đang chạy";
            case SUCCEEDED -> "Thành công";
            case PARTIAL -> "Thành công một phần";
            case FAILED -> "Lỗi";
        };
    }

    private static @Nullable String format(@Nullable Instant instant, ZoneId zone) {
        return instant == null ? null : FORMAT.format(instant.atZone(zone));
    }
}
