package com.sunasterisk.socialanalytics.entity;

/** What started a run of the metrics job. Stored by name (VARCHAR). */
public enum RunTrigger {
    /** The hourly schedule. */
    SCHEDULED,
    /** An admin, from the API or the dashboard. */
    MANUAL
}
