package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.SocialMetric;
import java.time.Instant;

public record MetricResponse(
        Long id,
        Long postId,
        long likes,
        long shares,
        long comments,
        long followers,
        Instant capturedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MetricResponse from(SocialMetric metric) {
        return new MetricResponse(metric.getId(), metric.getPost().getId(), metric.getLikes(), metric.getShares(),
                metric.getComments(), metric.getFollowers(), metric.getCapturedAt(), metric.getCreatedAt(),
                metric.getUpdatedAt());
    }
}
