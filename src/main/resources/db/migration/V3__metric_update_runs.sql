-- Background job that updates the metrics every hour (#10). Design: docs/01-thiet-ke-csdl.md

-- One row per run, failed runs included: the dashboard shows the last update time and the last run's status (#11).
-- Enum columns use the binary collation so CHECK is case-sensitive like Enum.valueOf (see V1).
CREATE TABLE metric_update_runs (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    trigger_type      VARCHAR(20)   COLLATE utf8mb4_0900_bin NOT NULL,
    triggered_by      BIGINT        NULL,
    status            VARCHAR(20)   COLLATE utf8mb4_0900_bin NOT NULL,
    started_at        DATETIME(6)   NOT NULL,
    finished_at       DATETIME(6)   NULL,
    accounts_total    INT           NOT NULL DEFAULT 0,
    accounts_failed   INT           NOT NULL DEFAULT 0,
    snapshots_created INT           NOT NULL DEFAULT 0,
    error_message     VARCHAR(1000) NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    CONSTRAINT pk_metric_update_runs PRIMARY KEY (id),
    CONSTRAINT fk_metric_update_runs_triggered_by FOREIGN KEY (triggered_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_metric_update_runs_trigger_type CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_metric_update_runs_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_metric_update_runs_finished_at CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT ck_metric_update_runs_counts
        CHECK (accounts_total >= 0 AND accounts_failed >= 0 AND snapshots_created >= 0),
    INDEX idx_metric_update_runs_started_at (started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Lock of ShedLock (JdbcTemplateLockProvider), schema from the ShedLock README for MySQL: at most one run at a time,
-- across every instance of the app. Times are the database's own clock (usingDbTime).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
