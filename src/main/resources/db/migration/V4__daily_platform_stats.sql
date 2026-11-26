-- Statistics per day (Asia/Ho_Chi_Minh) and platform, recomputed by the JMS listener (#12), read by the charts (#13).
-- Design: docs/01-thiet-ke-csdl.md. Derived data: always recomputed from posts + social_metrics, so no foreign key.
-- Each day is the state at the end of that day: every post counts with its latest snapshot up to then (Q12).
CREATE TABLE daily_platform_stats (
    stat_date      DATE         NOT NULL,
    platform       VARCHAR(20)  COLLATE utf8mb4_0900_bin NOT NULL,
    posts_tracked  INT          NOT NULL,
    posts_measured INT          NOT NULL,
    likes          BIGINT       NOT NULL,
    shares         BIGINT       NOT NULL,
    comments       BIGINT       NOT NULL,
    followers      BIGINT       NOT NULL,
    refreshed_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_daily_platform_stats PRIMARY KEY (stat_date, platform),
    CONSTRAINT ck_daily_platform_stats_platform CHECK (platform IN ('FACEBOOK', 'TWITTER')),
    CONSTRAINT ck_daily_platform_stats_counts CHECK (posts_tracked >= 0 AND posts_measured >= 0
        AND posts_measured <= posts_tracked AND likes >= 0 AND shares >= 0 AND comments >= 0 AND followers >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
