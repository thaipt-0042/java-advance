package com.sunasterisk.socialanalytics.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.sunasterisk.socialanalytics.support.LoginSession;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A file over the upload limit must get a 413 in the usual error format, not a reset connection. Needs a real
 * server: Tomcat enforces the multipart limits and swallows the rest of the body, MockMvc does neither.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ImportUploadLimitApiTests {

    private static final int MB = 1024 * 1024;
    private static final String BOUNDARY = "----import-limit-test";

    @LocalServerPort
    private int port;

    /** Just over the 5MB file limit, over the 6MB request limit, and far above both (but under max-swallow-size). */
    @ParameterizedTest
    @ValueSource(ints = {5 * MB + 1, 7 * MB, 40 * MB})
    void fileOverTheLimitGets413(int size) throws Exception {
        try (LoginSession admin = LoginSession.login(port, LoginSession.ADMIN, LoginSession.ADMIN_PASSWORD)) {
            HttpResponse<String> response = admin.send(upload(admin.request("/api/v1/import-posts"), size));

            assertProblem(response, 413, "CONTENT_TOO_LARGE");
        }
    }

    /**
     * Without the CSRF header: 403 whatever the size. The API never looks for a {@code _csrf} form field, which
     * would make Tomcat parse the multipart body inside the security filter and fail there with an ERROR log.
     */
    @ParameterizedTest
    @ValueSource(ints = {1024, 7 * MB, 40 * MB})
    void uploadWithoutCsrfHeaderGets403(int size, CapturedOutput output) throws Exception {
        try (LoginSession admin = LoginSession.login(port, LoginSession.ADMIN, LoginSession.ADMIN_PASSWORD)) {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/import-posts"));

            assertProblem(admin.send(upload(request, size)), 403, "FORBIDDEN");
        }
        assertThat(output.getOut()).doesNotContain("SizeLimitExceededException", "Servlet.service()");
    }

    @ParameterizedTest
    @ValueSource(ints = {1024, 7 * MB, 40 * MB})
    void uploadWithoutLoginGets401(int size) throws Exception {
        try (LoginSession anonymous = LoginSession.anonymous(port)) {
            assertProblem(anonymous.send(upload(anonymous.request("/api/v1/import-posts"), size)), 401,
                    "UNAUTHORIZED");
        }
    }

    private static HttpRequest upload(HttpRequest.Builder request, int size) {
        return request.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(size)))
                .build();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    }

    private static byte[] multipartBody(int fileSize) {
        byte[] content = new byte[fileSize];
        Arrays.fill(content, (byte) 'x');
        ByteArrayOutputStream body = new ByteArrayOutputStream(fileSize + 512);
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.xlsx\"\r\n"
                + "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }
}
