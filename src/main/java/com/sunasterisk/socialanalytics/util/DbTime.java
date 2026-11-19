package com.sunasterisk.socialanalytics.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Timestamps are stored in {@code DATETIME(6)} columns (microseconds), and MySQL rounds extra digits. Truncate
 * client-supplied instants first so that the value returned after a save, the value read back later and the value
 * compared in duplicate checks are all the same.
 */
public final class DbTime {

    private DbTime() {
    }

    public static Instant toColumnPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
