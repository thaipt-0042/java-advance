package com.sunasterisk.socialanalytics.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MetricUpdateRunTests {

    private static final Instant START = Instant.parse("2026-11-20T03:00:00Z");

    @Test
    void statusFollowsTheFailedAccounts() {
        assertThat(finished(3, 0).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(finished(3, 1).getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(finished(3, 3).getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(finished(0, 0).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(new MetricUpdateRun(RunTrigger.MANUAL, START).getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void longErrorMessagesAreCutToTheColumn() {
        MetricUpdateRun run = new MetricUpdateRun(RunTrigger.SCHEDULED, START);
        run.fail(START.plusSeconds(1), "x".repeat(1500));

        assertThat(run.getErrorMessage()).hasSize(MetricUpdateRun.ERROR_MESSAGE_MAX_LENGTH).endsWith("…");
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    private static MetricUpdateRun finished(int total, int failed) {
        MetricUpdateRun run = new MetricUpdateRun(RunTrigger.SCHEDULED, START);
        run.finish(START.plusSeconds(5), total, failed, total - failed, failed == 0 ? null : "x");
        return run;
    }
}
