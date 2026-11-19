package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.SocialMetric;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialMetricRepository extends JpaRepository<SocialMetric, Long> {

    List<SocialMetric> findByPostIdOrderByCapturedAtAsc(Long postId);
}
