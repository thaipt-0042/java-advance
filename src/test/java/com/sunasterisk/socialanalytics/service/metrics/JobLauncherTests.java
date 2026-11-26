package com.sunasterisk.socialanalytics.service.metrics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;

class JobLauncherTests {

    @Test
    void releasesTheLockEvenWhenTheRunThrows() {
        SimpleLock lock = mock(SimpleLock.class);

        assertThatThrownBy(() -> new JobLauncher().launch(() -> {
            throw new IllegalStateException("boom");
        }, lock)).hasMessage("boom");

        verify(lock).unlock();
    }
}
