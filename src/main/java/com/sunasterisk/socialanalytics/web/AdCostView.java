package com.sunasterisk.socialanalytics.web;

import com.sunasterisk.socialanalytics.service.ExchangeRateService.ExchangeRate;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The advertising-cost block of the dashboard (Q7): today's USD → VND rate from the SOAP service and the total cost
 * of the posts in both currencies, already formatted. Without a rate, only the USD total and a note.
 */
public record AdCostView(@Nullable String rate, String totalUsd, @Nullable String totalVnd, boolean rateUnavailable) {

    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");

    static AdCostView of(BigDecimal totalUsd, @Nullable ExchangeRate rate) {
        NumberFormat usd = NumberFormat.getNumberInstance(VIETNAMESE);
        usd.setMinimumFractionDigits(2);
        usd.setMaximumFractionDigits(2);
        NumberFormat vnd = NumberFormat.getIntegerInstance(VIETNAMESE);
        if (rate == null) {
            return new AdCostView(null, usd.format(totalUsd) + " USD", null, true);
        }
        return new AdCostView("1 USD = " + vnd.format(rate.rate()) + " VND (ngày " + rate.date() + ")",
                usd.format(totalUsd) + " USD", vnd.format(rate.convert(totalUsd)) + " VND", false);
    }
}
