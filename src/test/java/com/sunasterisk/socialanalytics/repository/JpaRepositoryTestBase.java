package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.config.JpaAuditingConfig;
import com.sunasterisk.socialanalytics.support.FixedClockConfiguration;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Repository tests on the real MySQL schema created by Flyway. Each test runs in a transaction
 * that is rolled back. The database is shared with other test classes, so never assert absolute counts.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class, FixedClockConfiguration.class})
@ActiveProfiles("test")
abstract class JpaRepositoryTestBase {
}
