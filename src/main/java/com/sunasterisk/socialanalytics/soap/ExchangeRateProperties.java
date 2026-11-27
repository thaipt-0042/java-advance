package com.sunasterisk.socialanalytics.soap;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the exchange-rate client calls ({@code app.exchange-rate.*}).
 *
 * @param url the SOAP service; empty for this app's own mock at {@code /soap}
 */
@ConfigurationProperties("app.exchange-rate")
@Validated
public record ExchangeRateProperties(
        @Nullable String url,
        @DefaultValue("PT2S") @NotNull Duration connectTimeout,
        @DefaultValue("PT5S") @NotNull Duration readTimeout) {
}
