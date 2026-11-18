package com.sunasterisk.socialanalytics.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Fixed clock for slice tests that don't load {@code ClockConfig}. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final Instant NOW = Instant.parse("2026-03-01T03:00:00Z");

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
