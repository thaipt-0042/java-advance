package com.sunasterisk.socialanalytics.client;

import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/** Mock of the Twitter API (see {@link MockSocialMetricsClient}). */
@Component
public class MockTwitterMetricsClient extends MockSocialMetricsClient {

    public MockTwitterMetricsClient(MetricsJobProperties properties, Clock clock, RandomGenerator random) {
        super(SocialPlatform.TWITTER, properties, clock, random);
    }
}
