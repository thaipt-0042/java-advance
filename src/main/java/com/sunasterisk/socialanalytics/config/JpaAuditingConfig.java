package com.sunasterisk.socialanalytics.config;

import com.sunasterisk.socialanalytics.util.DbTime;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Fills {@code @CreatedDate}/{@code @LastModifiedDate} from the application {@link Clock},
 * so tests can pin time with a fixed clock. Kept out of the main class so web slice tests don't need JPA.
 *
 * <p>Truncated to microseconds, the precision of the {@code DATETIME(6)} columns: otherwise the value returned
 * right after a save (nanoseconds) differs from the value read back later.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(DbTime.toColumnPrecision(Instant.now(clock)));
    }
}
