package com.sunasterisk.socialanalytics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.job.UpdateSocialMetricsJob;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The metrics job on MySQL, with the mock APIs of the test profile (no delay, no failure). The database is shared,
 * so a run updates every post in it; assertions look at this test's posts and runs.
 */
class JobApiTests extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private UpdateSocialMetricsJob scheduledJob;

    @Test
    void manualRunUpdatesEveryPostThenReportsTheRun() throws Exception {
        String t = token();
        long first = createPost("FACEBOOK", t + "-1", "page-" + t, "x");
        long second = createPost("FACEBOOK", t + "-2", "page-" + t, "y");

        long runId = startRun();
        Map<String, Object> run = awaitFinished(runId);

        assertThat(run).containsEntry("status", "SUCCEEDED").containsEntry("triggerType", "MANUAL")
                .containsEntry("accountsFailed", 0);
        assertThat((Integer) run.get("accountsTotal")).isPositive();
        assertThat((Integer) run.get("snapshotsCreated")).isGreaterThanOrEqualTo(2);
        assertThat(run.get("lastUpdatedAt")).isEqualTo(run.get("finishedAt"));
        // One snapshot per post, captured at the start of the run; followers are the account's, so the same.
        List<Map<String, Object>> snapshots = jdbcTemplate.queryForList(
                "select post_id, captured_at, followers from social_metrics where post_id in (?, ?)", first, second);
        assertThat(snapshots).hasSize(2);
        Instant startedAt = Instant.parse((String) run.get("startedAt"));
        assertThat(snapshots).allSatisfy(snapshot -> assertThat(
                ((LocalDateTime) snapshot.get("captured_at")).toInstant(ZoneOffset.UTC)).isEqualTo(startedAt));
        assertThat(snapshots.get(0).get("followers")).isEqualTo(snapshots.get(1).get("followers"));
    }

    @Test
    void manualRunWhileTheJobRunsIs409() throws Exception {
        SimpleLock held = lock();
        try {
            mockMvc.perform(post("/api/v1/jobs/update-metrics"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.detail").value("Job cập nhật số liệu đang chạy, hãy thử lại khi chạy xong"));
        } finally {
            held.unlock();
        }
        awaitFinished(startRun());
    }

    @Test
    void scheduledRunSkipsItsTurnWhileLockedAndRunsOtherwise() throws Exception {
        long before = runCount();
        SimpleLock held = lock();
        try {
            scheduledJob.updateSocialMetricsJob();
        } finally {
            held.unlock();
        }
        assertThat(runCount()).isEqualTo(before);

        scheduledJob.updateSocialMetricsJob();

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "select trigger_type, status from metric_update_runs order by id desc limit 1");
        assertThat(run).containsEntry("trigger_type", "SCHEDULED").containsEntry("status", "SUCCEEDED");
    }

    @Test
    void aRunLeftRunningByADeadInstanceIsMarkedFailedByTheNextRun() throws Exception {
        jdbcTemplate.update("""
                insert into metric_update_runs (trigger_type, status, started_at, created_at, updated_at)
                values ('SCHEDULED', 'RUNNING', '2026-01-01 03:00:00', now(6), now(6))
                """);
        long abandoned = jdbcTemplate.queryForObject("select max(id) from metric_update_runs", Long.class);

        awaitFinished(startRun());

        assertThat(jdbcTemplate.queryForMap(
                "select status, finished_at, error_message from metric_update_runs where id = ?", abandoned))
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", "Bị ngắt: ứng dụng dừng khi job đang chạy")
                .extractingByKey("finished_at").isNotNull();
    }

    @Test
    void aUserCanFollowTheJobButNotStartIt() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/update-metrics/latest").with(user("viewer").roles("USER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/jobs/update-metrics").with(user("viewer").roles("USER")))
                .andExpect(status().isForbidden());
    }

    private long startRun() throws Exception {
        String body = mockMvc.perform(post("/api/v1/jobs/update-metrics"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.runId")).longValue();
    }

    /** Polls the latest run until {@code runId} is no longer RUNNING (another run can't start meanwhile). */
    private Map<String, Object> awaitFinished(long runId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            String body = mockMvc.perform(get("/api/v1/jobs/update-metrics/latest"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            Map<String, Object> latest = JsonPath.read(body, "$");
            if (((Number) latest.get("runId")).longValue() == runId && !"RUNNING".equals(latest.get("status"))) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " did not finish");
    }

    private SimpleLock lock() {
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(Instant.now(),
                MetricsJobProperties.LOCK_NAME, Duration.ofMinutes(1), Duration.ZERO));
        assertThat(lock).as("job lock free").isPresent();
        return lock.get();
    }

    private long runCount() {
        return jdbcTemplate.queryForObject("select count(*) from metric_update_runs", Long.class);
    }
}
