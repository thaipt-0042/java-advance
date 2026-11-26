/**
 * The metrics job: {@link com.sunasterisk.socialanalytics.service.metrics.MetricUpdateService} runs it (on schedule
 * or by hand), {@link com.sunasterisk.socialanalytics.service.metrics.AccountMetricsUpdater} updates one account per
 * task in parallel, {@link com.sunasterisk.socialanalytics.service.metrics.JobLauncher} runs a manual run in the
 * background. The {@code job} package only holds the schedule and calls in here; nothing here imports it.
 */
package com.sunasterisk.socialanalytics.service.metrics;
