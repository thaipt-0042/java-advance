package com.sunasterisk.socialanalytics.security;

import static com.sunasterisk.socialanalytics.support.TestEntities.persisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.dto.LatestRunResponse;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import com.sunasterisk.socialanalytics.entity.UserRole;
import com.sunasterisk.socialanalytics.service.UserAccountService;
import com.sunasterisk.socialanalytics.service.metrics.MetricUpdateService;
import com.sunasterisk.socialanalytics.support.FixedClockConfiguration;
import com.sunasterisk.socialanalytics.web.DashboardController;
import com.sunasterisk.socialanalytics.web.LoginController;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Login with Facebook / X configured (fake client ids): the redirects to the providers, a failed callback, and the
 * pages as a logged-in Facebook / X user. The providers themselves are never called.
 */
@WebMvcTest(controllers = {LoginController.class, DashboardController.class}, properties = {
    "app.oauth2.facebook.client-id=fb-id",
    "app.oauth2.facebook.client-secret=fb-secret",
    "app.oauth2.twitter.client-id=x-id",
    "app.oauth2.twitter.client-secret=x-secret",
    "app.oauth2.token-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.demo-login.enabled=false",
})
@Import({SecurityConfig.class, FixedClockConfiguration.class})
class OAuth2LoginTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MetricUpdateService metricUpdateService;

    @MockitoBean
    private UserAccountService userAccountService;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void theLoginPageOffersBothProvidersAndNoDemoForm() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("href=\"/oauth2/authorization/facebook\""),
                        containsString("href=\"/oauth2/authorization/twitter\""),
                        containsString("Đăng nhập bằng X"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Tài khoản demo"))));
    }

    @Test
    void xUsesPkceWithS256() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/twitter"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        UriComponents uri = UriComponentsBuilder.fromUri(URI.create(location)).build();
        assertThat(uri.getHost()).isEqualTo("x.com");
        assertThat(uri.getPath()).isEqualTo("/i/oauth2/authorize");
        assertThat(uri.getQueryParams().getFirst("client_id")).isEqualTo("x-id");
        assertThat(uri.getQueryParams().getFirst("code_challenge")).isNotBlank();
        assertThat(uri.getQueryParams().getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(uri.getQueryParams().getFirst("state")).isNotBlank();
        assertThat(uri.getQueryParams().getFirst("scope")).isEqualTo("users.read%20tweet.read");
        assertThat(uri.getQueryParams().getFirst("redirect_uri"))
                .isEqualTo("http://localhost/login/oauth2/code/twitter");
    }

    @Test
    void facebookUsesTheConfiguredGraphVersion() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/facebook"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        UriComponents uri = UriComponentsBuilder.fromUri(URI.create(location)).build();
        assertThat(uri.getHost()).isEqualTo("www.facebook.com");
        assertThat(uri.getPath()).isEqualTo("/v27.0/dialog/oauth");
        // Spring Security 7 adds PKCE to every client by default (ClientSettings.requireProofKey).
        assertThat(uri.getQueryParams().getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(uri.getQueryParams().getFirst("scope")).isEqualTo("public_profile%20email");
    }

    @Test
    void aRefusedOrForgedCallbackGoesBackToTheLoginPageWithAMessage() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/twitter").param("error", "access_denied").param("state", "forged"))
                .andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(get("/login/oauth2/code/facebook").param("code", "c").param("state", "unknown"))
                .andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("Đăng nhập không thành công")));
    }

    @Test
    void anAdminLoggedInWithXSeesItsNameAndRunsTheJobAsItself() throws Exception {
        when(metricUpdateService.latest()).thenReturn(LatestRunResponse.of(null, null));
        when(metricUpdateService.startManual(any())).thenReturn(3L);
        SocialUser admin = socialUser(UserRole.ADMIN);

        mockMvc.perform(get("/dashboard").with(oauth2Login().oauth2User(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Sun* Việt Nam"), containsString("Chạy job ngay"))));
        mockMvc.perform(post("/dashboard/update-metrics").with(oauth2Login().oauth2User(admin)).with(csrf()))
                .andExpect(redirectedUrl("/dashboard"));

        verify(metricUpdateService).startManual(5L);
    }

    @Test
    void aUserLoggedInWithFacebookCannotRunTheJob() throws Exception {
        mockMvc.perform(post("/dashboard/update-metrics").with(oauth2Login().oauth2User(socialUser(UserRole.USER)))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static SocialUser socialUser(UserRole role) {
        User user = new User(SocialPlatform.TWITTER, "44196397", "Sun* Việt Nam");
        user.setRole(role);
        return new SocialUser(persisted(user, 5L, Instant.EPOCH, Instant.EPOCH));
    }
}
