package com.sunasterisk.socialanalytics.dto;

/** A manual run of the metrics job was started; follow it with {@code GET /api/v1/jobs/update-metrics/latest}. */
public record RunStartedResponse(long runId) {
}
