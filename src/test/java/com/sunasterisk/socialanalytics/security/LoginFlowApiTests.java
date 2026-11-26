package com.sunasterisk.socialanalytics.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.sunasterisk.socialanalytics.support.LoginSession;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole browser flow on a real server: form login, the {@code XSRF-TOKEN} cookie a JavaScript client reads,
 * a write with the token in the header, logout. (MockMvc doesn't show the cookie written by the CSRF filter.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LoginFlowApiTests {

    @LocalServerPort
    private int port;

    @Test
    void adminLogsInWritesWithTheCookieTokenAndLogsOut() throws Exception {
        try (LoginSession admin = LoginSession.login(port, LoginSession.ADMIN, LoginSession.ADMIN_PASSWORD)) {
            assertThat(admin.csrfToken()).as("XSRF-TOKEN cookie after login").isNotBlank();

            HttpResponse<String> created = admin.send(createPost(admin.request("/api/v1/posts")));
            assertThat(created.statusCode()).isEqualTo(201);

            HttpResponse<String> withoutHeader = admin.send(createPost(HttpRequest.newBuilder(
                    created.request().uri())));
            assertThat(withoutHeader.statusCode()).isEqualTo(403);
            assertThat(JsonPath.<String>read(withoutHeader.body(), "$.code")).isEqualTo("FORBIDDEN");

            HttpResponse<String> logout = admin.send(admin.request("/logout")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
            assertThat(logout.statusCode()).isEqualTo(302);
            assertThat(logout.headers().firstValue("Location")).hasValueSatisfying(location ->
                    assertThat(location).endsWith("/login?logout"));

            HttpResponse<String> afterLogout = admin.send(admin.request("/api/v1/posts").GET().build());
            assertThat(afterLogout.statusCode()).isEqualTo(401);
            assertThat(JsonPath.<String>read(afterLogout.body(), "$.code")).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void anApiReadGivesBackALostCsrfCookie() throws Exception {
        try (LoginSession admin = LoginSession.login(port, LoginSession.ADMIN, LoginSession.ADMIN_PASSWORD)) {
            admin.forgetCsrfCookie();
            assertThat(admin.csrfToken()).isNull();

            assertThat(admin.send(admin.request("/api/v1/posts?size=1").GET().build()).statusCode()).isEqualTo(200);

            assertThat(admin.csrfToken()).isNotBlank();
            assertThat(admin.send(createPost(admin.request("/api/v1/posts"))).statusCode()).isEqualTo(201);
        }
    }

    @Test
    void userLogsInAndCanReadButNotWrite() throws Exception {
        try (LoginSession user = LoginSession.login(port, LoginSession.USER, LoginSession.USER_PASSWORD)) {
            assertThat(user.send(user.request("/api/v1/posts").GET().build()).statusCode()).isEqualTo(200);
            assertThat(user.send(user.request("/api/v1/export-report").GET().build()).statusCode()).isEqualTo(200);

            HttpResponse<String> create = user.send(createPost(user.request("/api/v1/posts")));
            assertThat(create.statusCode()).isEqualTo(403);
            assertThat(JsonPath.<String>read(create.body(), "$.detail"))
                    .isEqualTo("Không có quyền thực hiện thao tác này");
        }
    }

    private static HttpRequest createPost(HttpRequest.Builder request) {
        return request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"platform": "FACEBOOK", "externalId": "login-%s", "accountName": "page"}
                        """.formatted(UUID.randomUUID())))
                .build();
    }
}
