package com.sunasterisk.socialanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.seed.DataSeeder;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SocialAnalyticsApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void doesNotSeedSampleDataOutsideDevProfile() {
        assertThat(context.getBeansOfType(DataSeeder.class)).isEmpty();
    }
}
