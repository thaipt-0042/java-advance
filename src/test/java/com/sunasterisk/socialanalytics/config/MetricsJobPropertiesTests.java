package com.sunasterisk.socialanalytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MetricsJobPropertiesTests {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void defaultsAndDisabledScheduleAreValid() {
        assertThat(violations(properties("0 0 * * * *", new MetricsJobProperties.Pool(4, 4, 1000),
                Duration.ofMinutes(5), Duration.ofMinutes(10), 0))).isEmpty();
        assertThat(violations(properties("-", new MetricsJobProperties.Pool(1, 1, 0),
                Duration.ofMinutes(5), Duration.ofMinutes(6), 1))).isEmpty();
    }

    @Test
    void rejectsWrongValues() {
        assertThat(violations(properties("every hour", new MetricsJobProperties.Pool(4, 4, 1000),
                Duration.ofMinutes(5), Duration.ofMinutes(10), 0)))
                .containsExactly("cronValid: cron must be a Spring cron expression (6 fields) or \"-\"");
        // 5 fields (Unix cron) is not a Spring cron expression
        assertThat(violations(properties("0 * * * *", new MetricsJobProperties.Pool(4, 4, 1000),
                Duration.ofMinutes(5), Duration.ofMinutes(10), 0))).hasSize(1);
        assertThat(violations(properties("-", new MetricsJobProperties.Pool(5, 4, -1),
                Duration.ofMinutes(5), Duration.ofMinutes(10), 0)))
                .containsExactlyInAnyOrder("pool.coreWithinMax: core-size must not exceed max-size",
                        "pool.queueCapacity: must be greater than or equal to 0");
        assertThat(violations(properties("-", new MetricsJobProperties.Pool(0, 65, 0),
                Duration.ofMinutes(5), Duration.ofMinutes(10), 0))).hasSize(2);
        assertThat(violations(properties("-", new MetricsJobProperties.Pool(4, 4, 0),
                Duration.ofMinutes(5), Duration.ofSeconds(359), 1.5)))
                .containsExactlyInAnyOrder(
                        "lockLongerThanRun: lock-at-most-for must be at least one minute longer than account-timeout",
                        "mock.failureRate: must be less than or equal to 1");
    }

    private static MetricsJobProperties properties(String cron, MetricsJobProperties.Pool pool, Duration timeout,
            Duration lock, double failureRate) {
        return new MetricsJobProperties(cron, pool, timeout, lock,
                new MetricsJobProperties.Mock(Duration.ZERO, failureRate));
    }

    private static Set<String> violations(MetricsJobProperties properties) {
        return validator.validate(properties).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.toSet());
    }
}
