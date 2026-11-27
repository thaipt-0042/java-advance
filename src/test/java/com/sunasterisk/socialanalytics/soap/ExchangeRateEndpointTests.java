package com.sunasterisk.socialanalytics.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.payload;
import static org.springframework.ws.test.server.ResponseMatchers.validPayload;

import java.math.BigDecimal;
import java.time.LocalDate;
import javax.xml.transform.Source;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webservices.test.autoconfigure.server.WebServiceServerTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

/** The mock SOAP service through the Spring-WS dispatcher, without HTTP. */
@WebServiceServerTest(endpoints = ExchangeRateEndpoint.class)
class ExchangeRateEndpointTests {

    private static final ClassPathResource XSD = new ClassPathResource("xsd/exchange-rates.xsd");

    @Autowired
    private MockWebServiceClient client;

    @Test
    void answersUsdToVndOfTheDayInTheSchemaFormat() throws Exception {
        client.sendRequest(withPayload(request("USD", "VND", "2026-03-01")))
                .andExpect(noFault())
                .andExpect(validPayload(XSD))
                .andExpect(payload(new StringSource("""
                        <getExchangeRateResponse xmlns="%s">
                          <from>USD</from><to>VND</to><date>2026-03-01</date><rate>%s</rate>
                        </getExchangeRateResponse>""".formatted(ExchangeRateEndpoint.NAMESPACE,
                        expectedUsdToVnd(LocalDate.parse("2026-03-01")).toPlainString()))));
    }

    @Test
    void theRateDependsOnlyOnTheDayAndStaysNearTwentyFiveThousand() {
        LocalDate day = LocalDate.parse("2026-03-01");
        assertThat(ExchangeRateEndpoint.rate("USD", "VND", day))
                .isEqualTo(ExchangeRateEndpoint.rate("USD", "VND", day))
                .isEqualTo(expectedUsdToVnd(day))
                .isBetween(new BigDecimal("25000"), new BigDecimal("25400"));
        assertThat(ExchangeRateEndpoint.rate("USD", "VND", day.plusDays(1)))
                .isNotEqualTo(ExchangeRateEndpoint.rate("USD", "VND", day));
        // VND → USD is the inverse, to 10 decimals; a currency to itself is 1.
        assertThat(ExchangeRateEndpoint.rate("VND", "USD", day).multiply(expectedUsdToVnd(day)))
                .isCloseTo(BigDecimal.ONE, within(new BigDecimal("0.000001")));
        assertThat(ExchangeRateEndpoint.rate("USD", "USD", day)).isEqualTo(BigDecimal.ONE);
    }

    @Test
    void anUnsupportedCurrencyIsAClientFault() {
        client.sendRequest(withPayload(request("USD", "EUR", "2026-03-01")))
                .andExpect(clientOrSenderFault("Unsupported currency pair USD/EUR (supported: USD, VND)"));
    }

    static Source request(String from, String to, String date) {
        return new StringSource("""
                <getExchangeRateRequest xmlns="%s"><from>%s</from><to>%s</to><date>%s</date></getExchangeRateRequest>
                """.formatted(ExchangeRateEndpoint.NAMESPACE, from, to, date));
    }

    /** The documented formula: 25,000 plus (epoch day × 7919) mod 401. */
    static BigDecimal expectedUsdToVnd(LocalDate day) {
        return BigDecimal.valueOf(25000 + Math.floorMod(day.toEpochDay() * 7919L, 401L));
    }
}
