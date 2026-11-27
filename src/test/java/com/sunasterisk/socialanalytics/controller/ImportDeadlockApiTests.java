package com.sunasterisk.socialanalytics.controller;

import static com.sunasterisk.socialanalytics.support.Xlsx.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.SocialMetricRepository;
import com.sunasterisk.socialanalytics.support.LockWaits;
import com.sunasterisk.socialanalytics.support.Xlsx;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.mysql.MySQLContainer;

/**
 * An import that loses a deadlock against another writer gets 409, like the UNIQUE race, instead of a 500. New posts
 * are inserted in file order, so two writers adding the same new posts in opposite orders can each hold one key and
 * wait for the other's.
 */
@ExtendWith(OutputCaptureExtension.class)
class ImportDeadlockApiTests extends ApiTestBase {

    @MockitoSpyBean
    private PostRepository postRepository;

    // Same bean overrides as ConcurrentWriteApiTests, so both classes share one application context.
    @MockitoSpyBean
    private SocialMetricRepository metricRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MySQLContainer mysql;

    @Test
    void importThatLosesADeadlockReturns409AndSavesNothing(CapturedOutput output) throws Exception {
        String t = token();
        // The import pauses after reading the existing posts (none), before its INSERT.
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        // A Spring Data repository is an interface proxy: the spy's default answer delegates to the real bean.
        Answer<?> realMethod = mockingDetails(postRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object posts = realMethod.answer(invocation);
            read.countDown();
            resume.await(10, TimeUnit.SECONDS);
            return posts;
        }).when(postRepository).findAllForUpdate(eq(SocialPlatform.FACEBOOK), any());
        byte[] file = Xlsx.of(row("platform", "externalId", "accountName"),
                row("FACEBOOK", t + "-111", "page"),
                row("FACEBOOK", t + "-222", "page"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            other.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Future<ResultActions> response = executor.submit(() -> mockMvc.perform(multipart("/api/v1/import-posts")
                    .file(new MockMultipartFile("file", t + ".xlsx", "application/octet-stream", file))));
            assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();

            // Another writer adds 222 first. Its extra rows make it the heavier transaction, so InnoDB picks the
            // import as the deadlock victim.
            insertPost(other, t + "-222");
            for (int i = 1; i <= 20; i++) {
                insertPost(other, t + "-other-" + i);
            }
            resume.countDown();
            // The import has inserted 111 and waits for 222.
            LockWaits.awaitRowLockWait(mysql, response);
            // The other writer now waits for the import's 111: deadlock, the import is rolled back.
            insertPost(other, t + "-111");

            response.get(30, TimeUnit.SECONDS)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.detail").value(
                            "Dữ liệu xung đột với dữ liệu hiện có, hãy tải lại rồi thử lại"));
            other.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from posts where external_id like ?",
                Integer.class, t + "-%")).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from import_batches where file_name = ?",
                Integer.class, t + ".xlsx")).isZero();
        assertThat(output).contains("Lock conflict").contains("Deadlock");
    }

    private static void insertPost(Connection connection, String externalId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into posts (platform, external_id, account_name, created_at, updated_at)
                values ('FACEBOOK', ?, 'other', now(6), now(6))
                """)) {
            insert.setString(1, externalId);
            insert.executeUpdate();
        }
    }
}
