package com.sunasterisk.socialanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One successful Excel import of posts (all-or-nothing, so failed imports have no row).
 *
 * <p>The table also has {@code uploaded_by} (filled once login exists, #9) and {@code stats_refreshed_at} (filled by
 * the JMS listener, #12); they are mapped when those issues use them.
 */
@Entity
@Table(name = "import_batches")
public class ImportBatch extends BaseEntity {

    public static final int FILE_NAME_MAX_LENGTH = 255;

    /** Original file name, for display only (never used as a path). */
    @Column(name = "file_name", nullable = false, length = FILE_NAME_MAX_LENGTH)
    private String fileName;

    /** Non-blank data rows in the file. */
    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "created_posts", nullable = false)
    private int createdPosts;

    /** Existing posts whose data changed; rows identical to the stored post are not counted. */
    @Column(name = "updated_posts", nullable = false)
    private int updatedPosts;

    @Column(name = "snapshots_created", nullable = false)
    private int snapshotsCreated;

    /** {@code captured_at} shared by every snapshot of this import; null when the file had no metrics. */
    @Column(name = "captured_at")
    private Instant capturedAt;

    protected ImportBatch() {
    }

    public ImportBatch(String fileName, int totalRows, int createdPosts, int updatedPosts, int snapshotsCreated,
            Instant capturedAt) {
        this.fileName = fileName;
        this.totalRows = totalRows;
        this.createdPosts = createdPosts;
        this.updatedPosts = updatedPosts;
        this.snapshotsCreated = snapshotsCreated;
        this.capturedAt = capturedAt;
    }

    public String getFileName() {
        return fileName;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getCreatedPosts() {
        return createdPosts;
    }

    public int getUpdatedPosts() {
        return updatedPosts;
    }

    public int getSnapshotsCreated() {
        return snapshotsCreated;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
