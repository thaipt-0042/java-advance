package com.sunasterisk.socialanalytics.controller;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

/**
 * A row deleted by another transaction between the moment a request reads it and the moment it writes must give
 * 404, not a 500 from Hibernate ("unexpected row count"). Each test deletes the row in an open transaction, starts
 * the request, waits until MySQL reports the request blocked on that row's lock, then commits.
 */
class ConcurrentDeleteApiTests extends ApiTestBase {

    private static final String METRIC_UPDATE = """
            {"capturedAt": "2026-03-05T00:00:00Z", "likes": 1, "shares": 0, "comments": 0, "followers": 0,
             "updatedAt": "2026-03-01T00:00:00Z"}
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MySQLContainer mysql;

    @Test
    void deletingPostThatIsBeingDeletedReturns404() throws Exception {
        long id = createPost("FACEBOOK", "cd-" + token(), "page", "c");

        performWhileUncommitted("delete from posts where id = ?", id,
                () -> mockMvc.perform(delete("/api/v1/posts/{id}", id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updatingPostThatIsBeingDeletedReturns404() throws Exception {
        long id = createPost("FACEBOOK", "cu-" + token(), "page", "c");

        performWhileUncommitted("delete from posts where id = ?", id,
                () -> mockMvc.perform(put("/api/v1/posts/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountName": "new", "updatedAt": "2026-03-01T00:00:00Z"}
                                """)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingSnapshotWhosePostIsBeingDeletedReturns404() throws Exception {
        long postId = createPost("TWITTER", "cm-" + token(), "page", "c");
        long metricId = createMetric(postId, "2026-03-01T00:00:00Z", 1);

        // Deleting the post removes (and locks) its snapshots through ON DELETE CASCADE.
        performWhileUncommitted("delete from posts where id = ?", postId,
                () -> mockMvc.perform(put("/api/v1/metrics/{id}", metricId)
                        .contentType(MediaType.APPLICATION_JSON).content(METRIC_UPDATE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingSnapshotThatIsBeingDeletedReturns404() throws Exception {
        long postId = createPost("TWITTER", "cm-" + token(), "page", "c");
        long metricId = createMetric(postId, "2026-03-01T00:00:00Z", 1);

        performWhileUncommitted("delete from social_metrics where id = ?", metricId,
                () -> mockMvc.perform(delete("/api/v1/metrics/{id}", metricId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addingSnapshotToPostThatIsBeingDeletedReturns404() throws Exception {
        long postId = createPost("FACEBOOK", "ca-" + token(), "page", "c");

        // Without the shared lock on the post, the insert fails on the foreign key and the client gets a 409.
        performWhileUncommitted("delete from posts where id = ?", postId,
                () -> mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON)
                        .content(metricJson(postId, "2026-03-01T00:00:00Z", 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private ResultActions performWhileUncommitted(String deleteSql, long id, Callable<ResultActions> request)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResultActions> response = new TransactionTemplate(transactionManager).execute(tx -> {
                jdbcTemplate.update(deleteSql, id);
                Future<ResultActions> pending = executor.submit(request);
                awaitBlockedOnRowLock(pending);
                return pending;
            });
            return response.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Waits until MySQL reports a row lock wait, i.e. the request is blocked on the row deleted above.
     * (performance_schema is read instead of information_schema.innodb_trx, which is a cache refreshed only
     * when not read for 0.1s.)
     */
    private void awaitBlockedOnRowLock(Future<?> pending) {
        String rootPassword = mysql.getEnvMap().get("MYSQL_ROOT_PASSWORD");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", rootPassword);
                Statement statement = root.createStatement()) {
            while (Instant.now().isBefore(deadline)) {
                if (pending.isDone()) {
                    // Finished without touching the locked row (e.g. it read nothing): nothing to wait for.
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
        fail("Request was not blocked by the uncommitted delete within 10s");
    }
}
