package com.sunasterisk.socialanalytics.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.lob.DefaultLobHandler;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService.OAuth2AuthorizedClientHolder;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService.OAuth2AuthorizedClientParametersMapper;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService.OAuth2AuthorizedClientRowMapper;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Spring Security's JDBC store of OAuth2 tokens ({@code oauth2_authorized_client}), with the token columns encrypted
 * ({@link TokenCipher}). The table and the SQL stay those of {@link JdbcOAuth2AuthorizedClientService}; only the
 * values change on the way in and out:
 *
 * <ul>
 *   <li>Writing: every BLOB parameter (access token, refresh token) is encrypted.</li>
 *   <li>Reading: the row mapper reads BLOBs through a {@code LobHandler}, which decrypts. A value that can't be
 *       decrypted (the key changed) makes the client absent, as if the user had never logged in: they log in
 *       again and a new token is stored. Not a 500.</li>
 * </ul>
 */
final class EncryptedTokens {

    private EncryptedTokens() {
    }

    static JdbcOAuth2AuthorizedClientService authorizedClientService(JdbcOperations jdbc,
            ClientRegistrationRepository registrations, TokenCipher cipher) {
        JdbcOAuth2AuthorizedClientService service = new JdbcOAuth2AuthorizedClientService(jdbc, registrations);
        service.setAuthorizedClientParametersMapper(new EncryptingParametersMapper(cipher));
        service.setAuthorizedClientRowMapper(new DecryptingRowMapper(registrations, cipher));
        return service;
    }

    private static final class EncryptingParametersMapper extends OAuth2AuthorizedClientParametersMapper {

        private final TokenCipher cipher;

        EncryptingParametersMapper(TokenCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public List<SqlParameterValue> apply(OAuth2AuthorizedClientHolder holder) {
            return super.apply(holder).stream()
                    .map(parameter -> parameter.getSqlType() == Types.BLOB
                            && parameter.getValue() instanceof byte[] plain
                                    ? new SqlParameterValue(Types.BLOB, cipher.encrypt(plain))
                                    : parameter)
                    // Mutable: the update statement reorders the list.
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private static final class DecryptingRowMapper extends OAuth2AuthorizedClientRowMapper {

        DecryptingRowMapper(ClientRegistrationRepository registrations, TokenCipher cipher) {
            super(registrations);
            setLobHandler(new DefaultLobHandler() {
                @Override
                public byte @Nullable [] getBlobAsBytes(ResultSet rs, String columnName) throws SQLException {
                    byte[] stored = super.getBlobAsBytes(rs, columnName);
                    return stored == null ? null : cipher.decrypt(stored);
                }
            });
        }

        @Override
        public @Nullable OAuth2AuthorizedClient mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                return super.mapRow(rs, rowNum);
            } catch (TokenCipher.TokenDecryptionException ex) {
                return null;
            }
        }
    }
}
