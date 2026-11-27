package com.sunasterisk.socialanalytics.service.metrics;

import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.dto.LatestRunResponse;
import com.sunasterisk.socialanalytics.entity.MetricUpdateRun;
import com.sunasterisk.socialanalytics.entity.RunStatus;
import com.sunasterisk.socialanalytics.entity.RunTrigger;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository;
import com.sunasterisk.socialanalytics.repository.AccountPostRepository.Account;
import com.sunasterisk.socialanalytics.repository.MetricUpdateRunRepository;
import com.sunasterisk.socialanalytics.service.StatsChanges;
import com.sunasterisk.socialanalytics.util.DbTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the metrics job ({@code docs/03-kien-truc.md}, section 4): one task per account in parallel, then the run's
 * outcome in {@code metric_update_runs}.
 *
 * <p>Only one run at a time, across instances: both entry points hold the ShedLock lock
 * {@value MetricsJobProperties#LOCK_NAME} for the whole run. The schedule takes it through {@code @SchedulerLock}
 * (and skips its turn when it is taken); a manual run takes it in the request, so a run already going anywhere
 * gives 409. Right after taking it, runs left {@code RUNNING} by a dead instance are marked {@code FAILED}: with the
 * lock held, no run can really be in progress.
 */
@Service
public class MetricUpdateService {

    private static final Logger log = LoggerFactory.getLogger(MetricUpdateService.class);

    static final String INTERRUPTED = "Bị ngắt: ứng dụng dừng khi job đang chạy";

    private final MetricUpdateRunRepository runRepository;
    private final AccountPostRepository accountPostRepository;
    private final AccountMetricsUpdater accountUpdater;
    private final JobLauncher jobLauncher;
    private final LockProvider lockProvider;
    private final StatsChanges statsChanges;
    private final MetricsJobProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MetricUpdateService(MetricUpdateRunRepository runRepository, AccountPostRepository accountPostRepository,
            AccountMetricsUpdater accountUpdater, JobLauncher jobLauncher, LockProvider lockProvider,
            StatsChanges statsChanges, MetricsJobProperties properties, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.accountPostRepository = accountPostRepository;
        this.accountUpdater = accountUpdater;
        this.jobLauncher = jobLauncher;
        this.lockProvider = lockProvider;
        this.statsChanges = statsChanges;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** A scheduled run, in the calling thread. The caller holds the lock ({@code @SchedulerLock}). */
    public void runScheduled() {
        execute(startRun(RunTrigger.SCHEDULED));
    }

    /**
     * Starts a manual run in the background and returns its id at once.
     *
     * @throws ConflictException a run is already going (on this instance or another)
     */
    public long startManual() {
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(clock.instant(),
                MetricsJobProperties.LOCK_NAME, properties.lockAtMostFor(), Duration.ZERO));
        if (lock.isEmpty()) {
            throw new ConflictException("Job cập nhật số liệu đang chạy, hãy thử lại khi chạy xong");
        }
        try {
            long runId = startRun(RunTrigger.MANUAL);
            jobLauncher.launch(() -> execute(runId), lock.get());
            return runId;
        } catch (RuntimeException ex) {
            // Not launched (e.g. TaskRejectedException): nobody else will release the lock. A run row left RUNNING
            // is marked FAILED by the next run.
            lock.get().unlock();
            throw ex;
        }
    }

    /** The most recent run and the last update time, for the API and the dashboard. */
    @Transactional(readOnly = true)
    public LatestRunResponse latest() {
        return LatestRunResponse.of(runRepository.findFirstByOrderByStartedAtDescIdDesc().orElse(null),
                runRepository.findFirstByStatusInOrderByFinishedAtDescIdDesc(
                                List.of(RunStatus.SUCCEEDED, RunStatus.PARTIAL))
                        .map(MetricUpdateRun::getFinishedAt)
                        .orElse(null));
    }

    /** Marks abandoned runs FAILED and creates the new RUNNING run, in one short transaction. */
    long startRun(RunTrigger trigger) {
        Long runId = transaction.execute(status -> {
            Instant now = DbTime.toColumnPrecision(clock.instant());
            int abandoned = runRepository.failRunning(now, INTERRUPTED);
            if (abandoned > 0) {
                log.warn("Metrics job: marked {} interrupted run(s) as FAILED", abandoned);
            }
            return runRepository.save(new MetricUpdateRun(trigger, now)).getId();
        });
        if (runId == null) {
            throw new IllegalStateException("Run not created");
        }
        return runId;
    }

    /**
     * Updates every account in parallel and records the outcome. Accounts not done within
     * {@code app.metrics-job.account-timeout} count as failed. Never throws: an unexpected error marks the run
     * FAILED.
     */
    public void execute(long runId) {
        MetricUpdateRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Run " + runId + " not found"));
        try {
            List<Account> accounts = accountPostRepository.findAccounts();
            log.info("Metrics job run {} ({}): {} accounts", runId, run.getTriggerType(), accounts.size());
            List<CompletableFuture<AccountResult>> tasks = accounts.stream()
                    .map(account -> submit(runId, run.getStartedAt(), account))
                    .toList();
            List<AccountResult> results = await(runId, accounts, tasks);
            finish(runId, results);
        } catch (RuntimeException ex) {
            log.error("Metrics job run {} failed", runId, ex);
            update(runId, failed -> failed.fail(now(), "Lỗi không mong đợi: " + ex.getMessage()));
        }
    }

    private CompletableFuture<AccountResult> submit(long runId, Instant capturedAt, Account account) {
        try {
            return accountUpdater.update(runId, capturedAt, account);
        } catch (TaskRejectedException ex) {
            log.warn("Metrics job run {}: account {} not queued: {}", runId, account, ex.getMessage());
            return CompletableFuture.completedFuture(AccountResult.failed(account, "Hàng đợi của job đã đầy"));
        }
    }

    /** Results in the order of the accounts; past the deadline, unfinished accounts count as failed. */
    private List<AccountResult> await(long runId, List<Account> accounts,
            List<CompletableFuture<AccountResult>> tasks) {
        long deadline = System.nanoTime() + properties.accountTimeout().toNanos();
        List<AccountResult> results = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                results.add(tasks.get(i).get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException ex) {
                log.warn("Metrics job run {}: account {} not done after {}", runId, accounts.get(i),
                        properties.accountTimeout());
                results.add(AccountResult.failed(accounts.get(i), "Quá thời gian chờ"));
            } catch (ExecutionException ex) {
                results.add(AccountResult.failed(accounts.get(i), String.valueOf(ex.getCause())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the accounts", ex);
            }
        }
        return results;
    }

    private void finish(long runId, List<AccountResult> results) {
        List<AccountResult> failures = results.stream().filter(AccountResult::isFailed).toList();
        int snapshots = results.stream().mapToInt(AccountResult::snapshotsCreated).sum();
        String summary = failures.isEmpty() ? null : "%d/%d tài khoản lỗi: %s".formatted(failures.size(),
                results.size(), failures.stream()
                        .map(failure -> failure.account() + " (" + failure.error() + ")")
                        .collect(Collectors.joining("; ")));
        MetricUpdateRun finished = update(runId, run -> {
            run.finish(now(), results.size(), failures.size(), snapshots, summary);
            if (snapshots > 0) {
                // Sent after this transaction commits (METRICS_UPDATED).
                statsChanges.metricsUpdated(runId, run.getStartedAt());
            }
        });
        log.info("Metrics job run {} {}: {} accounts, {} failed, {} snapshots", runId, finished.getStatus(),
                results.size(), failures.size(), snapshots);
    }

    private MetricUpdateRun update(long runId, Consumer<MetricUpdateRun> change) {
        return transaction.execute(status -> {
            MetricUpdateRun run = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalStateException("Run " + runId + " not found"));
            change.accept(run);
            return run;
        });
    }

    private Instant now() {
        return DbTime.toColumnPrecision(clock.instant());
    }
}
