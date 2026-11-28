package com.sunasterisk.socialanalytics.soap;

import com.sunasterisk.socialanalytics.soap.generated.GetExchangeRateRequest;
import com.sunasterisk.socialanalytics.soap.generated.GetExchangeRateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * Mock exchange-rate SOAP service ({@code docs/03-kien-truc.md}, section 7). USD → VND is 25,000 plus a variation
 * of up to 400 that depends only on the date (the same rate for the same day, every time); VND → USD is its
 * inverse, a currency to itself is 1. Any other currency is a SOAP client fault.
 */
@Endpoint
public class ExchangeRateEndpoint {

    public static final String NAMESPACE = "http://sunasterisk.com/socialanalytics/exchange-rates";

    private static final Set<String> SUPPORTED = Set.of("USD", "VND");
    private static final BigDecimal BASE = new BigDecimal("25000");

    @PayloadRoot(namespace = NAMESPACE, localPart = "getExchangeRateRequest")
    @ResponsePayload
    public GetExchangeRateResponse getExchangeRate(@RequestPayload GetExchangeRateRequest request) {
        String from = request.getFrom();
        String to = request.getTo();
        if (!SUPPORTED.contains(from) || !SUPPORTED.contains(to)) {
            throw new UnsupportedCurrencyException(from, to);
        }
        LocalDate date = request.getDate().toGregorianCalendar().toZonedDateTime().toLocalDate();
        GetExchangeRateResponse response = new GetExchangeRateResponse();
        response.setFrom(from);
        response.setTo(to);
        response.setDate(request.getDate());
        response.setRate(rate(from, to, date));
        return response;
    }

    static BigDecimal rate(String from, String to, LocalDate date) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        BigDecimal usdToVnd = BASE.add(BigDecimal.valueOf(Math.floorMod(date.toEpochDay() * 7919L, 401L)));
        return from.equals("USD") ? usdToVnd : BigDecimal.ONE.divide(usdToVnd, 10, RoundingMode.HALF_UP);
    }
}
