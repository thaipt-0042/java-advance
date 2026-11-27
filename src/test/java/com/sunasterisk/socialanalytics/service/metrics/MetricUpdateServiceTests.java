package com.sunasterisk.socialanalytics.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.entity.MetricUpdateRun;
import com.sunasterisk.socialanalytics.entity.RunStatus;
import com.sunasterisk.socialanalytics.entity.RunTrigger;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository.Account;
import com.sunasterisk.socialanalytics.repository.MetricUpdateRunRepository;
import com.sunasterisk.socialanalytics.service.StatsChanges;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class MetricUpdateServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-20T03:00:00.123456Z");
    private static final Account PAGE_A = new Account(SocialPlatform.FACEBOOK, "page-a");
    private static final Account PAGE_B = new Account(SocialPlatform.TWITTER, "page-b");

    private final MetricUpdateRunRepository runRepository = mock(MetricUpdateRunRepository.class);
    private final AccountPostRepository accountPostRepository = mock(AccountPostRepository.class);
    private final AccountMetricsUpdater accountUpdater = mock(AccountMetricsUpdater.class);
    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final LockProvider lockProvider = mock(LockProvider.class);
    private final StatsChanges statsChanges = mock(StatsChanges.class);
    private final MetricUpdateService service = service(Duration.ofSeconds(5));
    private final MetricUpdateRun run = run(7L, RunTrigger.SCHEDULED);

    @Test
    void allAccountsSucceeded() {
        accounts(PAGE_A, PAGE_B);
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_A)).thenReturn(done(AccountResult.succeeded(PAGE_A, 3)));
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_B)).thenReturn(done(AccountResult.succeeded(PAGE_B, 2)));

        service.execute(7L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getFinishedAt()).isEqualTo(NOW_MICROS);
        assertThat(run.getAccountsTotal()).isEqualTo(2);
        assertThat(run.getAccountsFailed()).isZero();
        assertThat(run.getSnapshotsCreated()).isEqualTo(5);
        assertThat(run.getErrorMessage()).isNull();
        verify(statsChanges).metricsUpdated(7L, NOW_MICROS);
    }

    @Test
    void oneFailedAccountMakesThePartialRunAndIsNamed() {
        accounts(PAGE_A, PAGE_B);
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_A)).thenReturn(done(AccountResult.failed(PAGE_A, "timeout")));
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_B)).thenReturn(done(AccountResult.succeeded(PAGE_B, 2)));

        service.execute(7L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getAccountsFailed()).isEqualTo(1);
        assertThat(run.getSnapshotsCreated()).isEqualTo(2);
        assertThat(run.getErrorMessage()).isEqualTo("1/2 tài khoản lỗi: FACEBOOK/page-a (timeout)");
    }

    @Test
    void everyAccountFailedIsAFailedRun() {
        accounts(PAGE_A);
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_A)).thenReturn(done(AccountResult.failed(PAGE_A, "down")));

        service.execute(7L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void noAccountIsASucceededRunWithNothingDone() {
        accounts();

        service.execute(7L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getAccountsTotal()).isZero();
        // No snapshot written: nothing to recompute.
        verify(statsChanges, never()).metricsUpdated(anyLong(), any());
    }

    @Test
    void accountsNotDoneInTimeCountAsFailed() {
        MetricUpdateService impatient = service(Duration.ofMillis(100));
        accounts(PAGE_A, PAGE_B);
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_A)).thenReturn(new CompletableFuture<>());
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_B)).thenReturn(done(AccountResult.succeeded(PAGE_B, 1)));

        long start = System.nanoTime();
        impatient.execute(7L);

        // One deadline for the whole run, not one per account.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getErrorMessage()).isEqualTo("1/2 tài khoản lỗi: FACEBOOK/page-a (Quá thời gian chờ)");
    }

    @Test
    void anAccountTheQueueRejectsCountsAsFailed() {
        accounts(PAGE_A, PAGE_B);
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_A)).thenThrow(new TaskRejectedException("full"));
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_B)).thenReturn(done(AccountResult.succeeded(PAGE_B, 1)));

        service.execute(7L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getErrorMessage()).contains("FACEBOOK/page-a (Hàng đợi của job đã đầy)");
    }

    @Test
    void aTaskCompletingExceptionallyCountsAsFailed() {
        accounts(PAGE_A);
        when(accountUpdater.update(7L, NOW_MICROS, PAGE_A))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        service.execute(7L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("boom");
    }

    @Test
    void anUnexpectedErrorFailsTheRun() {
        when(accountPostRepository.findAccounts()).thenThrow(new DataAccessResourceFailureException("db down"));

        service.execute(7L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("Lỗi không mong đợi: db down");
    }

    @Test
    void scheduledRunMarksInterruptedRunsThenRuns() {
        accounts();
        when(runRepository.save(any())).thenReturn(run);

        service.runScheduled();

        InOrder order = inOrder(runRepository);
        order.verify(runRepository).failRunning(NOW_MICROS, MetricUpdateService.INTERRUPTED);
        ArgumentCaptor<MetricUpdateRun> created = ArgumentCaptor.forClass(MetricUpdateRun.class);
        order.verify(runRepository).save(created.capture());
        assertThat(created.getValue().getTriggerType()).isEqualTo(RunTrigger.SCHEDULED);
        assertThat(created.getValue().getStartedAt()).isEqualTo(NOW_MICROS);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void manualRunWhileTheLockIsTakenIs409() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        assertThatThrownBy(service::startManual)
                .isInstanceOf(ConflictException.class)
                .hasMessage("Job cập nhật số liệu đang chạy, hãy thử lại khi chạy xong");
        verify(runRepository, never()).save(any());
    }

    @Test
    void manualRunTakesTheLockAndHandsItToTheLauncher() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
        MetricUpdateRun manual = run(9L, RunTrigger.MANUAL);
        when(runRepository.save(any())).thenReturn(manual);

        assertThat(service.startManual()).isEqualTo(9L);

        ArgumentCaptor<LockConfiguration> config = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(config.capture());
        assertThat(config.getValue().getName()).isEqualTo("metrics-job");
        assertThat(config.getValue().getLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
        verify(jobLauncher).launch(any(), eq(lock));
        verify(lock, never()).unlock();
    }

    @Test
    void manualRunNotLaunchedReleasesTheLock() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
        MetricUpdateRun manual = run(9L, RunTrigger.MANUAL);
        when(runRepository.save(any())).thenReturn(manual);
        doThrow(new TaskRejectedException("busy")).when(jobLauncher).launch(any(), any());

        assertThatThrownBy(service::startManual).isInstanceOf(TaskRejectedException.class);
        verify(lock).unlock();
    }

    @Test
    void latestRunAndLastUpdateTime() {
        MetricUpdateRun failed = run(8L, RunTrigger.MANUAL);
        failed.fail(NOW_MICROS, "x");
        MetricUpdateRun succeeded = run(7L, RunTrigger.SCHEDULED);
        succeeded.finish(Instant.parse("2026-11-20T02:00:05Z"), 1, 0, 1, null);
        when(runRepository.findFirstByOrderByStartedAtDescIdDesc()).thenReturn(Optional.of(failed));
        when(runRepository.findFirstByStatusInOrderByFinishedAtDescIdDesc(
                List.of(RunStatus.SUCCEEDED, RunStatus.PARTIAL))).thenReturn(Optional.of(succeeded));

        assertThat(service.latest()).satisfies(latest -> {
            assertThat(latest.runId()).isEqualTo(8L);
            assertThat(latest.status()).isEqualTo(RunStatus.FAILED);
            assertThat(latest.lastUpdatedAt()).isEqualTo(Instant.parse("2026-11-20T02:00:05Z"));
        });
    }

    @Test
    void latestBeforeAnyRun() {
        when(runRepository.findFirstByOrderByStartedAtDescIdDesc()).thenReturn(Optional.empty());
        when(runRepository.findFirstByStatusInOrderByFinishedAtDescIdDesc(any())).thenReturn(Optional.empty());

        assertThat(service.latest()).hasAllNullFieldsOrProperties();
    }

    private MetricUpdateService service(Duration accountTimeout) {
        MetricsJobProperties properties = new MetricsJobProperties("-", new MetricsJobProperties.Pool(4, 4, 10),
                accountTimeout, Duration.ofMinutes(10), new MetricsJobProperties.Mock(Duration.ZERO, 0));
        return new MetricUpdateService(runRepository, accountPostRepository, accountUpdater, jobLauncher,
                lockProvider, statsChanges, properties, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")),
                mock(PlatformTransactionManager.class));
    }

    private MetricUpdateRun run(long id, RunTrigger trigger) {
        MetricUpdateRun created = new MetricUpdateRun(trigger, NOW_MICROS);
        ReflectionTestUtils.setField(created, "id", id);
        when(runRepository.findById(id)).thenReturn(Optional.of(created));
        return created;
    }

    private void accounts(Account... accounts) {
        when(accountPostRepository.findAccounts()).thenReturn(List.of(accounts));
    }

    private static CompletableFuture<AccountResult> done(AccountResult result) {
        return CompletableFuture.completedFuture(result);
    }
}
