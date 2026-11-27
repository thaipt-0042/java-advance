-- Login with Facebook / X (#9). Design: docs/01-thiet-ke-csdl.md

-- Spring Session JDBC: login sessions in MySQL, so a restart does not log everyone out and every instance reads the
-- OAuth2 state. Copied verbatim from org/springframework/session/jdbc/schema-mysql.sql in spring-session-jdbc 4.1.2
-- (the repository reads and writes exactly these names); spring.session.jdbc.initialize-schema=never.
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BLOB NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

-- Access tokens of the logins (JdbcOAuth2AuthorizedClientService). Columns as in oauth2-client-schema.sql of
-- spring-security-oauth2-client 7.1.3, with the MySQL table options. The token columns hold AES-GCM ciphertext
-- (docs/cau-hoi-lam-ro.md Q8), never the token itself.
CREATE TABLE oauth2_authorized_client (
    client_registration_id  VARCHAR(100)  NOT NULL,
    principal_name          VARCHAR(200)  NOT NULL,
    access_token_type       VARCHAR(100)  NOT NULL,
    access_token_value      BLOB          NOT NULL,
    access_token_issued_at  TIMESTAMP     NOT NULL,
    access_token_expires_at TIMESTAMP     NOT NULL,
    access_token_scopes     VARCHAR(1000) DEFAULT NULL,
    refresh_token_value     BLOB          DEFAULT NULL,
    refresh_token_issued_at TIMESTAMP     DEFAULT NULL,
    created_at              TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_oauth2_authorized_client PRIMARY KEY (client_registration_id, principal_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
