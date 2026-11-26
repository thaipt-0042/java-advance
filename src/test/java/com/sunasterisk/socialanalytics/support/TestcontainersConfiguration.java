package com.sunasterisk.socialanalytics.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Real MySQL for tests. One container is shared by every Spring test context in the JVM,
 * so data committed by one test class is visible to the next: tests must not assert absolute row counts.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("social_analytics")
            .withUsername("social")
            .withPassword("social");

    static {
        MYSQL.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return MYSQL;
    }
}
