package com.sunasterisk.socialanalytics.support;

import com.sunasterisk.socialanalytics.entity.BaseEntity;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sets the fields that JPA fills (id, audit timestamps) on entities built in unit tests, where there is no
 * database or auditing.
 */
public final class TestEntities {

    private TestEntities() {
    }

    public static <T extends BaseEntity> T persisted(T entity, long id, Instant createdAt, Instant updatedAt) {
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "createdAt", createdAt);
        ReflectionTestUtils.setField(entity, "updatedAt", updatedAt);
        return entity;
    }
}
