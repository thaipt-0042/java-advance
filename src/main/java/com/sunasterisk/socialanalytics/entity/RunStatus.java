package com.sunasterisk.socialanalytics.entity;

/** State of a run of the metrics job. Stored by name (VARCHAR). */
public enum RunStatus {
    RUNNING,
    /** Every account was updated. */
    SUCCEEDED,
    /** Some accounts failed, the others were updated. */
    PARTIAL,
    /** No account was updated, or the run was interrupted. */
    FAILED
}
