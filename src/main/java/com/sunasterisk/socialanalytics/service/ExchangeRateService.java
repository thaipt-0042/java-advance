package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.soap.ExchangeRateClient;
import com.sunasterisk.socialanalytics.soap.ExchangeRateUnavailableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The USD → VND rate used to convert advertising costs ({@code docs/cau-hoi-lam-ro.md} Q7), from the SOAP service.
 * A day's rate is kept in memory once fetched (it doesn't change for that day); failures are not cached, so the
 * next call tries again.
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    public static final String USD = "USD";
    public static final String VND = "VND";
    private static final int CACHED_DAYS = 31;

    private final ExchangeRateClient client;
    private final Clock clock;
    private final Map<LocalDate, BigDecimal> usdToVnd = new ConcurrentHashMap<>();

    public ExchangeRateService(ExchangeRateClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /** @throws ExchangeRateUnavailableException the service could not answer */
    public ExchangeRate usdToVnd(LocalDate date) {
        BigDecimal cached = usdToVnd.get(date);
        if (cached != null) {
            return new ExchangeRate(USD, VND, date, cached);
        }
        BigDecimal rate = client.rate(USD, VND, date);
        if (usdToVnd.size() >= CACHED_DAYS) {
            usdToVnd.clear();
        }
        usdToVnd.put(date, rate);
        return new ExchangeRate(USD, VND, date, rate);
    }

    /** Today's rate, or empty when the service can't answer (logged): pages and exports show the rest anyway. */
    public Optional<ExchangeRate> usdToVndToday() {
        try {
            return Optional.of(usdToVnd(LocalDate.now(clock)));
        } catch (ExchangeRateUnavailableException ex) {
            log.warn("No exchange rate: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public record ExchangeRate(String from, String to, LocalDate date, BigDecimal rate) {

        /** {@code amount} in {@code from}, converted to {@code to} and rounded to whole units. */
        public BigDecimal convert(BigDecimal amount) {
            return amount.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        }
    }
}
