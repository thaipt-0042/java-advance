package com.sunasterisk.socialanalytics.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

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

    /**
     * The broker of docker-compose.yml, with the same queues and redelivery settings. The image was renamed from
     * apache/activemq-artemis when Artemis left ActiveMQ; it is the same server.
     */
    private static final ArtemisContainer ARTEMIS = new ArtemisContainer(DockerImageName.parse("apache/artemis:2.53.0")
            .asCompatibleSubstituteFor("apache/activemq-artemis"))
            .withCopyFileToContainer(MountableFile.forHostPath("docker/artemis/broker.properties"),
                    "/var/lib/artemis-instance/etc-override/broker.properties");

    static {
        MYSQL.start();
        ARTEMIS.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return MYSQL;
    }

    /**
     * The broker's address through the same variables as in production, so the broker URL (and its options, such as
     * {@code consumerWindowSize}) comes from application.properties rather than from a service connection.
     */
    @Bean
    DynamicPropertyRegistrar artemisProperties() {
        return registry -> {
            registry.add("ARTEMIS_HOST", ARTEMIS::getHost);
            registry.add("ARTEMIS_PORT", () -> ARTEMIS.getMappedPort(61616));
            registry.add("ARTEMIS_USER", ARTEMIS::getUser);
            registry.add("ARTEMIS_PASSWORD", ARTEMIS::getPassword);
        };
    }
}
