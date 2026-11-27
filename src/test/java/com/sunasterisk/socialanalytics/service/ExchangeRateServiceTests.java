package com.sunasterisk.socialanalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.service.ExchangeRateService.ExchangeRate;
import com.sunasterisk.socialanalytics.soap.ExchangeRateClient;
import com.sunasterisk.socialanalytics.soap.ExchangeRateUnavailableException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ExchangeRateServiceTests {

    // 2026-03-01 00:30 in Asia/Ho_Chi_Minh, still 02-28 in UTC
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-28T17:30:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final LocalDate TODAY = LocalDate.parse("2026-03-01");

    private final ExchangeRateClient client = mock(ExchangeRateClient.class);
    private final ExchangeRateService service = new ExchangeRateService(client, CLOCK);

    @Test
    void todayIsTheDayInVietnamAndItsRateIsAskedOnce() {
        when(client.rate("USD", "VND", TODAY)).thenReturn(new BigDecimal("25100"));

        assertThat(service.usdToVndToday()).contains(new ExchangeRate("USD", "VND", TODAY, new BigDecimal("25100")));
        assertThat(service.usdToVnd(TODAY).rate()).isEqualByComparingTo("25100");
        verify(client, times(1)).rate("USD", "VND", TODAY);
    }

    @Test
    void aFailureIsNotKeptSoTheNextCallTriesAgain() {
        when(client.rate("USD", "VND", TODAY))
                .thenThrow(new ExchangeRateUnavailableException("timeout"))
                .thenReturn(new BigDecimal("25100"));

        assertThat(service.usdToVndToday()).isEmpty();
        assertThat(service.usdToVndToday()).isPresent();
        verify(client, times(2)).rate("USD", "VND", TODAY);
    }

    @Test
    void convertsToWholeDongRoundingHalfUp() {
        ExchangeRate rate = new ExchangeRate("USD", "VND", TODAY, new BigDecimal("25000"));
        assertThat(rate.convert(new BigDecimal("12.34"))).isEqualByComparingTo("308500");
        assertThat(rate.convert(new BigDecimal("0.00002"))).isEqualByComparingTo("1"); // 0.5 → 1
        assertThat(rate.convert(new BigDecimal("0.00001"))).isEqualByComparingTo("0"); // 0.25 → 0
    }
}
