package com.sunasterisk.socialanalytics.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.ws.test.client.RequestMatchers.connectionTo;
import static org.springframework.ws.test.client.RequestMatchers.payload;
import static org.springframework.ws.test.client.ResponseCreators.withClientOrSenderFault;
import static org.springframework.ws.test.client.ResponseCreators.withPayload;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;

class ExchangeRateClientTests {

    private static final String URL = "http://rates.example.test/soap";
    private static final LocalDate DAY = LocalDate.parse("2026-03-01");

    @Test
    void sendsTheRequestAndReadsTheRate() {
        ExchangeRateClient client = client(URL, Duration.ofSeconds(5));
        MockWebServiceServer server = MockWebServiceServer.createServer(client.template());
        server.expect(connectionTo(URL))
                .andExpect(payload(ExchangeRateEndpointTests.request("USD", "VND", "2026-03-01")))
                .andRespond(withPayload(new StringSource("""
                        <getExchangeRateResponse xmlns="%s">
                          <from>USD</from><to>VND</to><date>2026-03-01</date><rate>25123.5</rate>
                        </getExchangeRateResponse>""".formatted(ExchangeRateEndpoint.NAMESPACE))));

        assertThat(client.rate("USD", "VND", DAY)).isEqualByComparingTo("25123.5");
        server.verify();
    }

    @Test
    void aSoapFaultMeansTheRateIsUnavailable() {
        ExchangeRateClient client = client(URL, Duration.ofSeconds(5));
        MockWebServiceServer server = MockWebServiceServer.createServer(client.template());
        server.expect(connectionTo(URL)).andRespond(withClientOrSenderFault("Unsupported", Locale.ENGLISH));

        assertThatThrownBy(() -> client.rate("USD", "VND", DAY))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasMessageContaining("fault: Unsupported");
    }

    @Test
    void aSlowServiceTimesOutAfterTheReadTimeout() throws IOException {
        HttpServer slow = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slow.createContext("/soap", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.start();
        try {
            ExchangeRateClient client = client("http://localhost:" + slow.getAddress().getPort() + "/soap",
                    Duration.ofMillis(300));
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.rate("USD", "VND", DAY))
                    .isInstanceOf(ExchangeRateUnavailableException.class)
                    .hasMessageContaining("unavailable");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void anUnreachableServiceIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ExchangeRateClient client = client("http://localhost:" + closedPort + "/soap", Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.rate("USD", "VND", DAY))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void withoutAUrlItCallsItsOwnMockOnTheServerPort() {
        MockEnvironment environment = new MockEnvironment();
        ExchangeRateClient client = new ExchangeRateClient(properties(null, Duration.ofSeconds(5)), environment);
        // No server (mock-servlet test, or not started yet): nothing to call.
        assertThatThrownBy(client::url).isInstanceOf(ExchangeRateUnavailableException.class);

        environment.setProperty("local.server.port", "18080");
        assertThat(client.url()).isEqualTo("http://localhost:18080/soap");
        assertThat(client(URL, Duration.ofSeconds(5)).url()).isEqualTo(URL);
    }

    private static ExchangeRateClient client(String url, Duration readTimeout) {
        return new ExchangeRateClient(properties(url, readTimeout), new MockEnvironment());
    }

    private static ExchangeRateProperties properties(@Nullable String url, Duration readTimeout) {
        return new ExchangeRateProperties(url, Duration.ofSeconds(1), readTimeout);
    }
}
