package com.sunasterisk.socialanalytics.client;

import com.sunasterisk.socialanalytics.config.MetricsJobProperties;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/** Mock of the Facebook API (see {@link MockSocialMetricsClient}). */
@Component
public class MockFacebookMetricsClient extends MockSocialMetricsClient {

    public MockFacebookMetricsClient(MetricsJobProperties properties, Clock clock, RandomGenerator random) {
        super(SocialPlatform.FACEBOOK, properties, clock, random);
    }
}
