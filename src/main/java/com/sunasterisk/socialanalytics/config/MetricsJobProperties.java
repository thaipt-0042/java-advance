package com.sunasterisk.socialanalytics.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the metrics job ({@code app.metrics-job.*}). Checked at startup: a wrong value stops the app instead
 * of a job that silently never runs or exhausts the database pool.
 *
 * @param cron when the job runs (Spring cron, in {@code app.time-zone}); {@code -} turns the schedule off
 * @param accountTimeout how long a run waits for all its accounts; accounts not done by then count as failed
 * @param lockAtMostFor how long the lock is kept if the instance running the job dies; must exceed
 *     {@code accountTimeout} so the lock never expires under a run that is still going
 */
@ConfigurationProperties("app.metrics-job")
@Validated
public record MetricsJobProperties(
        @DefaultValue("0 0 * * * *") @NotBlank String cron,
        @DefaultValue @Valid Pool pool,
        @DefaultValue("PT5M") @NotNull Duration accountTimeout,
        @DefaultValue("PT10M") @NotNull Duration lockAtMostFor,
        @DefaultValue @Valid Mock mock) {

    /** Name of the ShedLock lock shared by the scheduled and the manual runs. */
    public static final String LOCK_NAME = "metrics-job";

    @AssertTrue(message = "cron must be a Spring cron expression (6 fields) or \"-\"")
    public boolean isCronValid() {
        return "-".equals(cron) || CronExpression.isValidExpression(cron);
    }

    @AssertTrue(message = "lock-at-most-for must be at least one minute longer than account-timeout")
    public boolean isLockLongerThanRun() {
        return accountTimeout == null || lockAtMostFor == null
                || lockAtMostFor.compareTo(accountTimeout.plusMinutes(1)) >= 0;
    }

    /**
     * Thread pool updating the accounts in parallel. Each thread holds a database connection while it writes, so
     * {@code maxSize} must stay below the connection pool (checked by {@code AsyncConfig}).
     */
    public record Pool(
            @DefaultValue("4") @Min(1) @Max(64) int coreSize,
            @DefaultValue("4") @Min(1) @Max(64) int maxSize,
            @DefaultValue("1000") @Min(0) int queueCapacity) {

        @AssertTrue(message = "core-size must not exceed max-size")
        public boolean isCoreWithinMax() {
            return coreSize <= maxSize;
        }
    }

    /** Behaviour of the mock social network APIs. */
    public record Mock(
            @DefaultValue("PT0.2S") @NotNull Duration delay,
            @DefaultValue("0") @DecimalMin("0") @DecimalMax("1") double failureRate) {
    }
}
