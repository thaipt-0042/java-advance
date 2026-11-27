-- One row per successful Excel import (#6). Design: docs/01-thiet-ke-csdl.md
-- Import is all-or-nothing, so there is no row for a failed import.
-- uploaded_by is filled once login exists (#9); stats_refreshed_at by the JMS listener (#12).

CREATE TABLE import_batches (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    file_name          VARCHAR(255) NOT NULL,
    uploaded_by        BIGINT       NULL,
    total_rows         INT          NOT NULL,
    created_posts      INT          NOT NULL,
    updated_posts      INT          NOT NULL,
    snapshots_created  INT          NOT NULL,
    captured_at        DATETIME(6)  NULL,
    stats_refreshed_at DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    CONSTRAINT pk_import_batches PRIMARY KEY (id),
    CONSTRAINT fk_import_batches_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_import_batches_counts
        CHECK (total_rows >= 0 AND created_posts >= 0 AND updated_posts >= 0 AND snapshots_created >= 0),
    INDEX idx_import_batches_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
