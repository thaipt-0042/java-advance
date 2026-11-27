package com.sunasterisk.socialanalytics.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.test.context.ActiveProfiles;

/** The token store on MySQL: what lands in {@code oauth2_authorized_client}, and reading it with another key. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EncryptedTokensTests {

    private static final Instant ISSUED = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ClientRegistration twitter = SocialClientRegistrations.of(new SocialLoginProperties(
            new SocialLoginProperties.Provider(null, null), new SocialLoginProperties.Provider("x-id", "x-secret"),
            "v27.0", TokenCipherTests.KEY, null)).getFirst();
    private final InMemoryClientRegistrationRepository registrations =
            new InMemoryClientRegistrationRepository(twitter);

    @Test
    void tokensAreStoredEncryptedAndReadBack() {
        String principal = "x-" + UUID.randomUUID();
        JdbcOAuth2AuthorizedClientService service = service(TokenCipherTests.KEY);

        service.saveAuthorizedClient(client(principal, "access-" + principal, "refresh-" + principal),
                new TestingAuthenticationToken(principal, null));

        byte[] storedAccess = jdbcTemplate.queryForObject("select access_token_value from oauth2_authorized_client "
                + "where client_registration_id = 'twitter' and principal_name = ?", byte[].class, principal);
        byte[] storedRefresh = jdbcTemplate.queryForObject("select refresh_token_value from oauth2_authorized_client "
                + "where client_registration_id = 'twitter' and principal_name = ?", byte[].class, principal);
        assertThat(new String(storedAccess, StandardCharsets.ISO_8859_1)).doesNotContain("access-");
        assertThat(new String(storedRefresh, StandardCharsets.ISO_8859_1)).doesNotContain("refresh-");

        OAuth2AuthorizedClient loaded = service.loadAuthorizedClient("twitter", principal);
        assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("access-" + principal);
        assertThat(loaded.getAccessToken().getScopes()).containsExactlyInAnyOrder("users.read", "tweet.read");
        assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("refresh-" + principal);
    }

    @Test
    void afterAKeyChangeTheOldTokenIsAbsentAndTheNextLoginReplacesIt() {
        String principal = "x-" + UUID.randomUUID();
        service(TokenCipherTests.KEY).saveAuthorizedClient(client(principal, "old", null),
                new TestingAuthenticationToken(principal, null));
        JdbcOAuth2AuthorizedClientService rotated = service(TokenCipherTests.OTHER_KEY);

        assertThat((OAuth2AuthorizedClient) rotated.loadAuthorizedClient("twitter", principal)).isNull();

        rotated.saveAuthorizedClient(client(principal, "new", null), new TestingAuthenticationToken(principal, null));
        assertThat(rotated.<OAuth2AuthorizedClient>loadAuthorizedClient("twitter", principal).getAccessToken()
                .getTokenValue()).isEqualTo("new");
        assertThat(jdbcTemplate.queryForObject("select count(*) from oauth2_authorized_client where principal_name = ?",
                Long.class, principal)).isEqualTo(1);
    }

    private JdbcOAuth2AuthorizedClientService service(String key) {
        return EncryptedTokens.authorizedClientService(jdbcTemplate, registrations, TokenCipher.fromBase64Key(key));
    }

    private OAuth2AuthorizedClient client(String principal, String access, String refresh) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, access, ISSUED,
                ISSUED.plusSeconds(7200), Set.of("users.read", "tweet.read"));
        return new OAuth2AuthorizedClient(twitter, principal, accessToken,
                refresh == null ? null : new OAuth2RefreshToken(refresh, ISSUED));
    }
}
