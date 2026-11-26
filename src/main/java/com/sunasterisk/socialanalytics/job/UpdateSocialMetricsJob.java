package com.sunasterisk.socialanalytics.job;

import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.service.metrics.MetricUpdateService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code updateSocialMetricsJob}: the hourly entry point of the metrics job ({@code app.metrics-job.cron}, in the
 * application time zone). Only schedules and locks; the work is in {@link MetricUpdateService}. When another run
 * holds the lock (manual run, or another instance), ShedLock skips this turn.
 */
@Component
public class UpdateSocialMetricsJob {

    private final MetricUpdateService metricUpdateService;

    public UpdateSocialMetricsJob(MetricUpdateService metricUpdateService) {
        this.metricUpdateService = metricUpdateService;
    }

    @Scheduled(cron = "${app.metrics-job.cron}", zone = "${app.time-zone}")
    @SchedulerLock(name = MetricsJobProperties.LOCK_NAME)
    public void updateSocialMetricsJob() {
        metricUpdateService.runScheduled();
    }
}
