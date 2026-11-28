package com.sunasterisk.socialanalytics.soap;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.sunasterisk.socialanalytics.support.LoginSession;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The SOAP mock over real HTTP and the app calling it as a client ({@code app.exchange-rate.url} empty: its own
 * {@code /soap} on the port it listens on).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ExchangeRateApiTests {

    @LocalServerPort
    private int port;

    @Test
    void theWsdlIsPublicAndPointsAtThisServer() throws Exception {
        try (LoginSession anonymous = LoginSession.anonymous(port)) {
            HttpResponse<String> wsdl = anonymous.send(anonymous.request("/soap/exchange-rates.wsdl").GET().build());

            assertThat(wsdl.statusCode()).isEqualTo(200);
            assertThat(wsdl.body())
                    .contains("targetNamespace=\"" + ExchangeRateEndpoint.NAMESPACE + "\"")
                    .contains("name=\"getExchangeRate\"")
                    .contains("location=\"http://localhost:" + port + "/soap\"");
        }
    }

    @Test
    void aSoapCallNeedsNoLoginNorCsrfToken() throws Exception {
        try (LoginSession anonymous = LoginSession.anonymous(port)) {
            HttpResponse<String> response = anonymous.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/soap"))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                              xmlns:er="%s">
                              <soapenv:Body>
                                <er:getExchangeRateRequest>
                                  <er:from>USD</er:from><er:to>VND</er:to><er:date>2026-03-01</er:date>
                                </er:getExchangeRateRequest>
                              </soapenv:Body>
                            </soapenv:Envelope>""".formatted(ExchangeRateEndpoint.NAMESPACE)))
                    .build());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).containsPattern("<(\\w+:)?rate>" + ExchangeRateEndpointTests.expectedUsdToVnd(
                    LocalDate.parse("2026-03-01")) + "</(\\w+:)?rate>");
        }
    }

    @Test
    void theApiGivesTheRateTheAppGotFromTheSoapService() throws Exception {
        try (LoginSession user = LoginSession.login(port, LoginSession.USER, LoginSession.USER_PASSWORD)) {
            HttpResponse<String> response = user.send(
                    user.request("/api/v1/exchange-rates/usd-vnd?date=2026-03-01").GET().build());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(response.body(), "$.from")).isEqualTo("USD");
            assertThat(JsonPath.<String>read(response.body(), "$.to")).isEqualTo("VND");
            assertThat(JsonPath.<String>read(response.body(), "$.date")).isEqualTo("2026-03-01");
            assertThat(new BigDecimal(JsonPath.read(response.body(), "$.rate").toString()))
                    .isEqualByComparingTo(ExchangeRateEndpointTests.expectedUsdToVnd(LocalDate.parse("2026-03-01")));
        }
    }

    @Test
    void theDashboardShowsTheCostInBothCurrencies() throws Exception {
        try (LoginSession user = LoginSession.login(port, LoginSession.USER, LoginSession.USER_PASSWORD)) {
            String page = user.send(user.request("/dashboard").GET().build()).body();

            assertThat(page).contains("id=\"ad-cost\"").contains("1 USD = ").doesNotContain("Không lấy được tỷ giá");
        }
    }
}
