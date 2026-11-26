package com.sunasterisk.socialanalytics.messaging;

/** Why the statistics must be recomputed ({@code docs/cau-hoi-lam-ro.md} Q5). */
public enum StatsRefreshType {
    /** An Excel import committed (asked by the course). */
    IMPORT_COMPLETED,
    /** A run of the metrics job wrote snapshots. */
    METRICS_UPDATED,
    /** Posts or snapshots changed through the API. */
    METRICS_CHANGED
}
