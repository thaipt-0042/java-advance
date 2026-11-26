package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.MetricUpdateRun;
import com.sunasterisk.socialanalytics.entity.RunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MetricUpdateRunRepository extends JpaRepository<MetricUpdateRun, Long> {

    /** The most recent run, whatever its status. */
    Optional<MetricUpdateRun> findFirstByOrderByStartedAtDescIdDesc();

    /** The most recent run in one of these states, by end time ("last updated": SUCCEEDED or PARTIAL). */
    Optional<MetricUpdateRun> findFirstByStatusInOrderByFinishedAtDescIdDesc(Collection<RunStatus> statuses);

    /**
     * Marks the runs left {@code RUNNING} as {@code FAILED}: their instance died mid-run. Only called while holding
     * the job lock, when no run can really be in progress. {@code finished_at} is never before {@code started_at}
     * (CHECK), even if the clock of the instance that started the run was ahead.
     */
    @Modifying
    @Query(value = """
            update metric_update_runs
            set status = 'FAILED', finished_at = greatest(:now, started_at), error_message = :reason, updated_at = :now
            where status = 'RUNNING'
            """, nativeQuery = true)
    int failRunning(@Param("now") Instant now, @Param("reason") String reason);
}
