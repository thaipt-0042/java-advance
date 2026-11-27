package com.sunasterisk.socialanalytics.service.metrics;

import com.sunasterisk.socialanalytics.config.AsyncConfig;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs a manual run in the background ({@code @Async}, one thread), so the request that started it returns at
 * once. The job lock was taken by the request; it is released here when the run ends, whatever happens. A separate
 * bean from {@link MetricUpdateService} because {@code @Async} only works through the Spring proxy.
 */
@Service
public class JobLauncher {

    @Async(AsyncConfig.LAUNCHER_EXECUTOR)
    public void launch(Runnable run, SimpleLock lock) {
        try {
            run.run();
        } finally {
            lock.unlock();
        }
    }
}
