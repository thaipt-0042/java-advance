package com.sunasterisk.socialanalytics.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Behind a proxy that terminates HTTPS ({@code FORWARD_HEADERS_STRATEGY=native}), the Location header must use the
 * public scheme and host from X-Forwarded-*. Needs a real server: Tomcat applies these headers, MockMvc does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=native")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ForwardedHeadersApiTests {

    @LocalServerPort
    private int port;

    @Test
    void locationUsesForwardedSchemeAndHost() throws Exception {
        String externalId = "fwd-" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/posts"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "social.example.com")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"platform": "FACEBOOK", "externalId": "%s", "accountName": "page"}
                        """.formatted(externalId)))
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(location ->
                assertThat(location).matches("https://social\\.example\\.com/api/v1/posts/\\d+"));
    }
}
