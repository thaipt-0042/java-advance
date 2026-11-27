package com.sunasterisk.socialanalytics.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A browser-like session against a real server ({@code RANDOM_PORT} tests): logs in through the demo form login
 * of the test profile, keeps the cookies, and sends the CSRF token back in the {@code X-XSRF-TOKEN} header like
 * the JavaScript clients do.
 */
public final class LoginSession implements AutoCloseable {

    public static final String ADMIN = "demo-admin";
    public static final String ADMIN_PASSWORD = "test-admin-password";
    public static final String USER = "demo-user";
    public static final String USER_PASSWORD = "test-user-password";

    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient client;

    private LoginSession(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    /** A session without login (still gets the CSRF cookie). */
    public static LoginSession anonymous(int port) throws IOException, InterruptedException {
        LoginSession session = new LoginSession(port);
        session.send(session.request("/login").GET().build());
        return session;
    }

    public static LoginSession login(int port, String username, String password)
            throws IOException, InterruptedException {
        LoginSession session = new LoginSession(port);
        String page = session.send(session.request("/login").GET().build()).body();
        Matcher csrf = CSRF_FIELD.matcher(page);
        assertThat(csrf.find()).as("CSRF field of the login page").isTrue();
        HttpResponse<String> login = session.send(session.request("/login")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form("username", username) + "&"
                        + form("password", password) + "&" + form("_csrf", csrf.group(1))))
                .build());
        assertThat(login.statusCode()).as("login redirect").isEqualTo(302);
        assertThat(login.headers().firstValue("Location")).as("login succeeded").hasValueSatisfying(location ->
                assertThat(location).doesNotContain("error"));
        // The token changes at login; any request loads the new one into the cookie.
        session.send(session.request("/api/v1/posts?size=1").GET().build());
        return session;
    }

    /** A request to {@code path} on this server, with the CSRF header (cookies are added by the client). */
    public HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        String token = csrfToken();
        return token == null ? builder : builder.header("X-XSRF-TOKEN", token);
    }

    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Every cookie of this session as a {@code Cookie} header value ({@code a=1; b=2}). */
    public String cookieHeader() {
        return cookies.getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
    }

    /** Value of a cookie of this session, or null. */
    public String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    public String csrfToken() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    /** Drops the CSRF cookie, as a browser would after the user clears it, keeping the login session. */
    public void forgetCsrfCookie() {
        cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .toList()
                .forEach(cookie -> cookies.getCookieStore().remove(null, cookie));
    }

    @Override
    public void close() {
        client.close();
    }

    private static String form(String name, String value) {
        return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
