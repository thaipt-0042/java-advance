package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.SocialMetric;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialMetricRepository extends JpaRepository<SocialMetric, Long> {

    List<SocialMetric> findByPostIdOrderByCapturedAtAsc(Long postId);

    boolean existsByPostIdAndCapturedAt(Long postId, Instant capturedAt);

    boolean existsByPostIdAndCapturedAtAndIdNot(Long postId, Instant capturedAt, Long id);

    /** Reads the snapshot and locks its row until the transaction ends; see {@code PostRepository}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from SocialMetric m where m.id = :id")
    Optional<SocialMetric> findByIdForUpdate(@Param("id") Long id);

    /** Deletes without loading the snapshot first; returns the number of deleted rows (0 if it doesn't exist). */
    @Modifying
    @Query("delete from SocialMetric m where m.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);

    /** Snapshots filtered by post and by {@code [from, to)}; {@code null} skips a filter. */
    @Query("""
            select m from SocialMetric m
            where (:postId is null or m.post.id = :postId)
              and (:from is null or m.capturedAt >= :from)
              and (:to is null or m.capturedAt < :to)
            """)
    Page<SocialMetric> search(@Param("postId") Long postId, @Param("from") Instant from, @Param("to") Instant to,
            Pageable pageable);
}
