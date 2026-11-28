package com.sunasterisk.socialanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One run of the metrics job, failed runs included. {@code startedAt} is also the {@code capturedAt} of every
 * snapshot the run creates.
 *
 * <p>{@code triggered_by} is the {@code users} row of the admin who started a manual run (null for a scheduled run
 * and for the demo accounts).
 */
@Entity
@Table(name = "metric_update_runs")
public class MetricUpdateRun extends BaseEntity {

    public static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private RunTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private RunStatus status;

    @Column(name = "triggered_by")
    private Long triggeredBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "accounts_total", nullable = false)
    private int accountsTotal;

    @Column(name = "accounts_failed", nullable = false)
    private int accountsFailed;

    @Column(name = "snapshots_created", nullable = false)
    private int snapshotsCreated;

    /** Summary of what went wrong; never contains a token. */
    @Column(name = "error_message", length = ERROR_MESSAGE_MAX_LENGTH)
    private String errorMessage;

    protected MetricUpdateRun() {
    }

    public MetricUpdateRun(RunTrigger triggerType, Instant startedAt) {
        this(triggerType, startedAt, null);
    }

    public MetricUpdateRun(RunTrigger triggerType, Instant startedAt, Long triggeredBy) {
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
        this.status = RunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    /**
     * Records the outcome of a run that went through every account: SUCCEEDED when none failed, PARTIAL when some
     * failed, FAILED when all failed. {@code errorSummary} names the failed accounts (null when none).
     */
    public void finish(Instant finishedAt, int accountsTotal, int accountsFailed, int snapshotsCreated,
            String errorSummary) {
        this.finishedAt = finishedAt;
        this.accountsTotal = accountsTotal;
        this.accountsFailed = accountsFailed;
        this.snapshotsCreated = snapshotsCreated;
        this.errorMessage = truncate(errorSummary);
        if (accountsFailed == 0) {
            this.status = RunStatus.SUCCEEDED;
        } else if (accountsFailed < accountsTotal) {
            this.status = RunStatus.PARTIAL;
        } else {
            this.status = RunStatus.FAILED;
        }
    }

    /** The run stopped before going through the accounts (or was interrupted): FAILED with the reason. */
    public void fail(Instant finishedAt, String reason) {
        this.finishedAt = finishedAt;
        this.status = RunStatus.FAILED;
        this.errorMessage = truncate(reason);
    }

    private static String truncate(String message) {
        return message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? message : message.substring(0, ERROR_MESSAGE_MAX_LENGTH - 1) + "…";
    }

    public RunTrigger getTriggerType() {
        return triggerType;
    }

    public Long getTriggeredBy() {
        return triggeredBy;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getAccountsTotal() {
        return accountsTotal;
    }

    public int getAccountsFailed() {
        return accountsFailed;
    }

    public int getSnapshotsCreated() {
        return snapshotsCreated;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
