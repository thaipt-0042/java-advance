package com.sunasterisk.socialanalytics.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunasterisk.socialanalytics.entity.RunStatus;
import com.sunasterisk.socialanalytics.support.LoginSession;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestClientXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

/**
 * STOMP over SockJS on a real server, as the dashboard does: logged-in session cookie on the handshake, masked
 * CSRF token from the page's meta tag in the CONNECT frame. Run over a real WebSocket and over XHR only (the
 * fallback when a proxy blocks WebSocket, which needs the HTTP-level CSRF check off for {@code /ws/**}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WebSocketApiTests {

    private static final Pattern META = Pattern.compile("<meta name=\"(_csrf(?:_header)?)\" content=\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private DashboardEventPublisher publisher;

    @Test
    void aLoggedInPageReceivesTheEventsOverWebSocket() throws Exception {
        receivesEvents(new WebSocketTransport(new StandardWebSocketClient()));
    }

    @Test
    void andOverXhrWhenWebSocketIsBlocked() throws Exception {
        receivesEvents(new RestClientXhrTransport());
    }

    @Test
    void connectWithoutTheCsrfTokenIsRefused() throws Exception {
        try (LoginSession user = LoginSession.login(port, LoginSession.USER, LoginSession.USER_PASSWORD)) {
            assertThatThrownBy(() -> connect(user, new WebSocketTransport(new StandardWebSocketClient()),
                    new StompHeaders()))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    void connectWithoutLoginIsRefused() throws Exception {
        try (LoginSession anonymous = LoginSession.anonymous(port)) {
            StompHeaders headers = new StompHeaders();
            headers.add("X-XSRF-TOKEN", "anything");
            assertThatThrownBy(() -> connect(anonymous, new WebSocketTransport(new StandardWebSocketClient()), headers))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    void subscribingToAnotherDestinationClosesTheSession() throws Exception {
        try (LoginSession user = LoginSession.login(port, LoginSession.USER, LoginSession.USER_PASSWORD)) {
            StompSession session = connect(user, new WebSocketTransport(new StandardWebSocketClient()),
                    csrfHeaders(user));

            session.subscribe("/topic/secret", new Collector(new LinkedBlockingQueue<>()));

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (session.isConnected() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(session.isConnected()).as("session closed by the server").isFalse();
        }
    }

    private void receivesEvents(Transport transport) throws Exception {
        try (LoginSession user = LoginSession.login(port, LoginSession.USER, LoginSession.USER_PASSWORD)) {
            StompSession session = connect(user, transport, csrfHeaders(user));
            BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
            session.subscribe("/topic/metrics", new Collector(received));

            // The subscription is registered asynchronously: publish until the first event arrives.
            Map<String, Object> event = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (event == null && System.nanoTime() < deadline) {
                publisher.publish(new DashboardEvent.StatsUpdated(Instant.now(), LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 11, 20)));
                event = received.poll(300, TimeUnit.MILLISECONDS);
            }
            assertThat(event).as("STATS_UPDATED received").isNotNull()
                    .containsEntry("type", "STATS_UPDATED")
                    .containsEntry("fromDate", "2026-11-01")
                    .containsEntry("toDate", "2026-11-20");

            received.clear();
            publisher.publish(new DashboardEvent.JobFinished(Instant.now(), 42, RunStatus.SUCCEEDED,
                    Instant.parse("2026-11-20T03:00:05Z")));
            Map<String, Object> job = received.poll(5, TimeUnit.SECONDS);
            assertThat(job).isNotNull().containsEntry("type", "JOB_FINISHED").containsEntry("runId", 42)
                    .containsEntry("status", "SUCCEEDED");
            session.disconnect();
        }
    }

    private StompSession connect(LoginSession login, Transport transport, StompHeaders connectHeaders)
            throws Exception {
        SockJsClient sockJs = new SockJsClient(List.of(transport));
        // The session cookie goes on the SockJS info and XHR requests too, not only on the WebSocket handshake.
        sockJs.setHttpHeaderNames("Cookie");
        WebSocketStompClient stomp = new WebSocketStompClient(sockJs);
        stomp.setMessageConverter(new JacksonJsonMessageConverter());
        // Every cookie, like a browser: the session, and XSRF-TOKEN, from which the handshake reads the token that
        // the masked one of the CONNECT frame is checked against.
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        String cookies = login.cookieHeader();
        if (!cookies.isEmpty()) {
            handshake.add("Cookie", cookies);
        }
        return stomp.connectAsync("http://localhost:" + port + "/ws", handshake, connectHeaders,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
    }

    /** The CSRF header name and masked token from the meta tags of the dashboard. */
    private static StompHeaders csrfHeaders(LoginSession login) throws Exception {
        String page = login.send(login.request("/dashboard").GET().build()).body();
        Matcher meta = META.matcher(page);
        String token = null;
        String header = null;
        while (meta.find()) {
            if (meta.group(1).equals("_csrf")) {
                token = meta.group(2);
            } else {
                header = meta.group(2);
            }
        }
        assertThat(header).isEqualTo("X-XSRF-TOKEN");
        assertThat(token).isNotBlank();
        StompHeaders headers = new StompHeaders();
        headers.add(header, token);
        return headers;
    }

    private record Collector(BlockingQueue<Map<String, Object>> received) implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            received.add((Map<String, Object>) payload);
        }
    }
}
