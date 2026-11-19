package com.sunasterisk.socialanalytics.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application clock in the business time zone ({@code app.time-zone}, default Asia/Ho_Chi_Minh).
 * Use {@code LocalDate.now(clock)} for "today" instead of the JVM default zone, which is UTC in containers.
 * An invalid zone id fails the application at startup.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.time-zone:Asia/Ho_Chi_Minh}") ZoneId zone) {
        return Clock.system(zone);
    }
}
