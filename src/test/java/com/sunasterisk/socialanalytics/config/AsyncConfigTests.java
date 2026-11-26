package com.sunasterisk.socialanalytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@ExtendWith(OutputCaptureExtension.class)
class AsyncConfigTests {

    private final AsyncConfig config = new AsyncConfig();

    @Test
    void accountThreadsMustStayBelowTheDatabasePool() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setMaximumPoolSize(10);

            ThreadPoolTaskExecutor executor = config.metricsAccountExecutor(properties(9), dataSource);
            assertThat(executor.getMaxPoolSize()).isEqualTo(9);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("metrics-account-");
            assertThatThrownBy(() -> config.metricsAccountExecutor(properties(10), dataSource))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("app.metrics-job.pool.max-size (10) must be below the database pool "
                            + "size (10)");
        }
    }

    @Test
    void uncaughtAsyncErrorsAreLogged(CapturedOutput output) throws Exception {
        config.getAsyncUncaughtExceptionHandler().handleUncaughtException(new IllegalStateException("lost"),
                Object.class.getMethod("toString"));

        assertThat(output.getOut()).contains("Background task Object.toString failed")
                .contains("IllegalStateException: lost");
    }

    private static MetricsJobProperties properties(int maxSize) {
        return new MetricsJobProperties("-", new MetricsJobProperties.Pool(1, maxSize, 10), Duration.ofMinutes(5),
                Duration.ofMinutes(10), new MetricsJobProperties.Mock(Duration.ZERO, 0));
    }
}
