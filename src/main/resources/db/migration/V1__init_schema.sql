-- Thiết kế chi tiết: docs/01-thiet-ke-csdl.md
-- Mọi cột thời gian lưu theo UTC (spring.jpa.properties.hibernate.jdbc.time_zone=UTC).

CREATE TABLE users (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    provider         VARCHAR(20)   NOT NULL,
    provider_user_id VARCHAR(100)  NOT NULL,
    email            VARCHAR(255)  NULL,
    display_name     VARCHAR(100)  NOT NULL,
    avatar_url       VARCHAR(1000) NULL,
    role             VARCHAR(20)   NOT NULL DEFAULT 'USER',
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT ck_users_provider CHECK (provider IN ('FACEBOOK', 'TWITTER')),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'USER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE posts (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    platform     VARCHAR(20)   NOT NULL,
    external_id  VARCHAR(100)  NOT NULL,
    account_name VARCHAR(100)  NOT NULL,
    url          VARCHAR(1000) NULL,
    content      VARCHAR(5000) NULL,
    published_at DATETIME(6)   NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    CONSTRAINT pk_posts PRIMARY KEY (id),
    CONSTRAINT uk_posts_platform_external_id UNIQUE (platform, external_id),
    CONSTRAINT ck_posts_platform CHECK (platform IN ('FACEBOOK', 'TWITTER')),
    INDEX idx_posts_platform_account (platform, account_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE social_metrics (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    post_id     BIGINT      NOT NULL,
    likes       BIGINT      NOT NULL DEFAULT 0,
    shares      BIGINT      NOT NULL DEFAULT 0,
    comments    BIGINT      NOT NULL DEFAULT 0,
    followers   BIGINT      NOT NULL DEFAULT 0,
    captured_at DATETIME(6) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_social_metrics PRIMARY KEY (id),
    CONSTRAINT fk_social_metrics_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT uk_social_metrics_post_captured UNIQUE (post_id, captured_at),
    CONSTRAINT ck_social_metrics_non_negative
        CHECK (likes >= 0 AND shares >= 0 AND comments >= 0 AND followers >= 0),
    INDEX idx_social_metrics_captured_at (captured_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
