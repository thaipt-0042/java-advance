package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.SocialMetric;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialMetricRepository extends JpaRepository<SocialMetric, Long> {

    List<SocialMetric> findByPostIdOrderByCapturedAtAsc(Long postId);

    boolean existsByPostIdAndCapturedAt(Long postId, Instant capturedAt);

    boolean existsByPostIdAndCapturedAtAndIdNot(Long postId, Instant capturedAt, Long id);

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
