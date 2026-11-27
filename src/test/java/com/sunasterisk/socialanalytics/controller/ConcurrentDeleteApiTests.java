package com.sunasterisk.socialanalytics.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.support.LockWaits;
import com.sunasterisk.socialanalytics.support.Xlsx;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

/**
 * A row deleted by another transaction between the moment a request reads it and the moment it writes must give
 * 404, not a 500 from Hibernate ("unexpected row count"). Each test deletes the row in an open transaction, starts
 * the request, waits until MySQL reports the request blocked on that row's lock, then commits. One import test
 * inserts the row that way instead.
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

    @Test
    void importingPostThatIsBeingDeletedCreatesItAgain() throws Exception {
        String externalId = "ci-" + token();
        long id = createPost("FACEBOOK", externalId, "page", "c");
        byte[] file = Xlsx.of(Xlsx.POST_HEADER, Xlsx.row("FACEBOOK", externalId, "renamed"));

        // Without the row lock the import reads the post, then its UPDATE matches 0 rows: 500.
        performWhileUncommitted("delete from posts where id = ?", id,
                () -> mockMvc.perform(multipart("/api/v1/import-posts").file(new MockMultipartFile("file",
                        "posts.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdPosts").value(1))
                .andExpect(jsonPath("$.updatedPosts").value(0));
    }

    @Test
    void importingPostThatIsBeingCreatedUpdatesItOnceCommitted() throws Exception {
        String externalId = "cc-" + token();
        byte[] file = Xlsx.of(Xlsx.POST_HEADER, Xlsx.row("FACEBOOK", externalId, "page-" + externalId));

        // The locking read waits for the uncommitted insert, then sees the committed post and updates it instead of
        // failing on the UNIQUE constraint.
        performWhileUncommitted("""
                        insert into posts (platform, external_id, account_name, created_at, updated_at)
                        values ('FACEBOOK', ?, 'other', now(6), now(6))
                        """, externalId,
                () -> mockMvc.perform(multipart("/api/v1/import-posts").file(new MockMultipartFile("file",
                        "posts.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdPosts").value(0))
                .andExpect(jsonPath("$.updatedPosts").value(1));
        mockMvc.perform(get("/api/v1/posts").param("q", "page-" + externalId))
                .andExpect(jsonPath("$.content[0].externalId").value(externalId));
    }

    private ResultActions performWhileUncommitted(String sql, Object param, Callable<ResultActions> request)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResultActions> response = new TransactionTemplate(transactionManager).execute(tx -> {
                jdbcTemplate.update(sql, param);
                Future<ResultActions> pending = executor.submit(request);
                LockWaits.awaitRowLockWait(mysql, pending);
                return pending;
            });
            return response.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
