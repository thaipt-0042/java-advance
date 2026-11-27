package com.sunasterisk.socialanalytics.client;

import java.util.Map;

/** Metrics of an account read from a social network: its followers, and the counts of its posts by external id. */
public record AccountMetrics(long followers, Map<String, PostMetrics> posts) {

    public record PostMetrics(long likes, long shares, long comments) {
    }
}
