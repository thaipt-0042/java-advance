package com.sunasterisk.socialanalytics.config;

import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background work of the metrics job ({@code docs/03-kien-truc.md}, section 4): the hourly schedule, the lock that
 * keeps two runs from overlapping (ShedLock on MySQL, so across instances too), and two executors:
 *
 * <ul>
 *   <li>{@value #ACCOUNT_EXECUTOR}: updates the accounts of a run in parallel.</li>
 *   <li>{@value #LAUNCHER_EXECUTOR} (one thread): runs a manual run in the background, so the HTTP request returns
 *       202 at once. It is a separate pool because the thread waiting for the accounts must not take a place in
 *       the pool of the tasks it waits for.</li>
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${app.metrics-job.lock-at-most-for}")
@EnableConfigurationProperties(MetricsJobProperties.class)
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String ACCOUNT_EXECUTOR = "metricsAccountExecutor";
    public static final String LAUNCHER_EXECUTOR = "jobLauncherExecutor";

    @Bean(ACCOUNT_EXECUTOR)
    ThreadPoolTaskExecutor metricsAccountExecutor(MetricsJobProperties properties, DataSource dataSource) {
        MetricsJobProperties.Pool pool = properties.pool();
        if (dataSource instanceof HikariDataSource hikari && pool.maxSize() >= hikari.getMaximumPoolSize()) {
            throw new IllegalStateException(("app.metrics-job.pool.max-size (%d) must be below the database pool "
                    + "size (%d): each account thread holds a connection while writing, and web requests need some")
                    .formatted(pool.maxSize(), hikari.getMaximumPoolSize()));
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.coreSize());
        executor.setMaxPoolSize(pool.maxSize());
        executor.setQueueCapacity(pool.queueCapacity());
        executor.setThreadNamePrefix("metrics-account-");
        // Finish the accounts in progress on shutdown instead of cutting their transactions.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(LAUNCHER_EXECUTOR)
    ThreadPoolTaskExecutor jobLauncherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // The lock allows one run at a time, so a second launch never waits here.
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("metrics-job-");
        return executor;
    }

    /** Lock table {@code shedlock}; times from the database clock, the same for every instance. */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    /**
     * Randomness of the mock APIs (failure rate). Thread-safe, as the account threads share it; a bean so tests
     * can replace it.
     */
    @Bean
    RandomGenerator random() {
        return new SecureRandom();
    }

    /**
     * Exceptions escaping a {@code void} {@code @Async} method (the manual run) have no caller to reach: log them
     * with the method, rather than lose them.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("Background task {}.{} failed",
                method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }
}
