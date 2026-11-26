package com.sunasterisk.socialanalytics.client;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.util.Collection;

/** Reads the current metrics of one account and its posts from a social network. One implementation per platform. */
public interface SocialMetricsClient {

    SocialPlatform platform();

    /**
     * Current followers of the account and current likes / shares / comments of the given posts. Posts the network
     * doesn't know (deleted there) are missing from the result.
     *
     * @throws SocialApiException the call failed (network, rate limit, ...); nothing was read
     */
    AccountMetrics fetch(String accountName, Collection<String> externalIds);
}
