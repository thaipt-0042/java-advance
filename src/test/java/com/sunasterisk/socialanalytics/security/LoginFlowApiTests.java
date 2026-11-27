package com.sunasterisk.socialanalytics.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.sunasterisk.socialanalytics.support.LoginSession;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole browser flow on a real server: form login, the {@code XSRF-TOKEN} cookie a JavaScript client reads,
 * a write with the token in the header, the dashboard form with the {@code _csrf} field, logout. (MockMvc doesn't show the cookie written by the CSRF filter.)
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
    void aWebRequestGivesBackALostCsrfCookie() throws Exception {
        // A web path whose response doesn't use the token: the cookie is still written.
        try (LoginSession admin = LoginSession.login(port, LoginSession.ADMIN, LoginSession.ADMIN_PASSWORD)) {
            admin.forgetCsrfCookie();

            assertThat(admin.send(admin.request("/actuator/health").GET().build()).statusCode()).isEqualTo(200);

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

    @Test
    void adminRunsTheJobFromTheDashboardForm() throws Exception {
        try (LoginSession admin = LoginSession.login(port, LoginSession.ADMIN, LoginSession.ADMIN_PASSWORD)) {
            HttpResponse<String> page = admin.send(admin.request("/dashboard").GET().build());
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Chạy job ngay", "demo-admin");
            Matcher field = Pattern.compile("action=\"/dashboard/update-metrics\"[\\s\\S]*?name=\"_csrf\" "
                    + "value=\"([^\"]+)\"").matcher(page.body());
            assertThat(field.find()).as("_csrf field of the run form").isTrue();

            // A browser form post: the masked token in the _csrf field, no header.
            HttpResponse<String> submitted = admin.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/dashboard/update-metrics"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + URLEncoder.encode(field.group(1),
                            StandardCharsets.UTF_8)))
                    .build());
            assertThat(submitted.statusCode()).isEqualTo(302);
            assertThat(submitted.headers().firstValue("Location")).hasValueSatisfying(location ->
                    assertThat(location).endsWith("/dashboard"));

            String after = admin.send(admin.request("/dashboard").GET().build()).body();
            assertThat(after).contains("Đã bắt đầu cập nhật số liệu");
            awaitNoRunningJob(admin);
        }
    }

    /** The run started here writes to the shared database: let it end before the next test. */
    private static void awaitNoRunningJob(LoginSession session) throws Exception {
        for (int i = 0; i < 600; i++) {
            String latest = session.send(session.request("/api/v1/jobs/update-metrics/latest").GET().build()).body();
            if (!"RUNNING".equals(JsonPath.read(latest, "$.status"))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The job is still running");
    }

    @Test
    void userSeesTheDashboardWithoutTheRunButton() throws Exception {
        try (LoginSession user = LoginSession.login(port, LoginSession.USER, LoginSession.USER_PASSWORD)) {
            HttpResponse<String> page = user.send(user.request("/dashboard").GET().build());
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Cập nhật lần cuối").doesNotContain("Chạy job ngay");
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
