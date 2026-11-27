package com.sunasterisk.socialanalytics.support;

import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;
import org.testcontainers.mysql.MySQLContainer;

/** Synchronizes concurrency tests on InnoDB lock waits. */
public final class LockWaits {

    private LockWaits() {
    }

    /**
     * Waits until MySQL reports a row lock wait, i.e. the pending request is blocked on a row locked by the test's
     * own open transaction. Returns early if the request finishes first (it didn't need the locked row).
     * (performance_schema is read instead of information_schema.innodb_trx, which is a cache refreshed only
     * when not read for 0.1s.)
     */
    public static void awaitRowLockWait(MySQLContainer mysql, Future<?> pending) {
        String rootPassword = mysql.getEnvMap().get("MYSQL_ROOT_PASSWORD");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", rootPassword);
                Statement statement = root.createStatement()) {
            while (Instant.now().isBefore(deadline)) {
                if (pending.isDone()) {
                    return;
                }
                try (ResultSet rs = statement.executeQuery("select count(*) from performance_schema.data_lock_waits")) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        fail("Request was not blocked on a row lock within 10s");
    }
}
