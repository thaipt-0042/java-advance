package com.sunasterisk.socialanalytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sunasterisk.socialanalytics.service.StatsChanges;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Messages on a real broker (Artemis in Testcontainers, with the redelivery settings of docker-compose). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class StatsMessagingTests {

    @MockitoSpyBean
    private JmsTemplate jmsTemplate;

    @Autowired
    private StatsChanges statsChanges;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        clearInvocations(jmsTemplate);
    }

    @Test
    void sentOnlyAfterTheTransactionCommits() {
        Instant capturedAt = Instant.parse("2026-03-01T18:00:00Z");

        transaction.executeWithoutResult(status -> {
            statsChanges.snapshotsChanged(capturedAt);
            // Still inside the transaction: nothing sent yet.
            verify(jmsTemplate, never()).convertAndSend(eq(StatsRefreshMessage.QUEUE), any(Object.class));
        });

        // 18:00 UTC is 01:00 the next day in Vietnam.
        verify(jmsTemplate).convertAndSend(StatsRefreshMessage.QUEUE,
                StatsRefreshMessage.metricsChanged(LocalDate.of(2026, 3, 2)));
    }

    @Test
    void notSentWhenTheTransactionRollsBack() {
        transaction.executeWithoutResult(status -> {
            statsChanges.snapshotsChanged(Instant.parse("2026-03-01T03:00:00Z"));
            status.setRollbackOnly();
        });

        verify(jmsTemplate, never()).convertAndSend(eq(StatsRefreshMessage.QUEUE), any(Object.class));
    }

    @Test
    void aMessageThatKeepsFailingEndsInTheDeadLetterQueueAndIsLogged(CapturedOutput output) throws Exception {
        // Earlier tests leave refresh messages in the shared queue (each takes up to ~1s); let them go first so the
        // timing below only measures the redeliveries.
        awaitEmptyQueue();
        String marker = "poison-" + Instant.now().toEpochMilli();
        // Claims to be a refresh message but isn't valid JSON: conversion fails on every delivery.
        jmsTemplate.send(StatsRefreshMessage.QUEUE, session -> {
            var message = session.createTextMessage("{not json " + marker);
            message.setStringProperty("_type", "stats-refresh");
            return message;
        });

        // 4 deliveries, 1s + 2s + 4s apart (docker/artemis/broker.properties), then the dead letter queue.
        long start = System.nanoTime();
        while (deadLetterLine(output, marker) == null) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("dead letter logged within 30s")
                    .isLessThan(Duration.ofSeconds(30));
            Thread.sleep(200);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("after the redelivery delays")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(7));
        assertThat(deadLetterLine(output, marker)).contains("ERROR")
                .contains("from " + StatsRefreshMessage.QUEUE);
        assertThat(output.getOut().lines().filter(line -> line.contains("Execution of JMS message listener failed")))
                .as("failed deliveries").hasSize(4);
    }

    private void awaitEmptyQueue() throws InterruptedException {
        long start = System.nanoTime();
        while (Boolean.TRUE.equals(jmsTemplate.browse(StatsRefreshMessage.QUEUE,
                (session, browser) -> browser.getEnumeration().hasMoreElements()))) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("queue drained")
                    .isLessThan(Duration.ofMinutes(3));
            Thread.sleep(200);
        }
    }

    private static String deadLetterLine(CapturedOutput output, String marker) {
        return output.getOut().lines()
                .filter(line -> line.contains("moved to " + StatsRefreshMessage.DEAD_LETTER_QUEUE)
                        && line.contains(marker))
                .findFirst().orElse(null);
    }

    @Test
    void aValidMessageIsProcessedByTheListener(CapturedOutput output) {
        jmsTemplate.convertAndSend(StatsRefreshMessage.QUEUE, StatsRefreshMessage.metricsChanged(LocalDate.now()));

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!output.getOut().contains("Recomputed statistics")) {
            assertThat(System.nanoTime()).as("listener ran within 20s").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
