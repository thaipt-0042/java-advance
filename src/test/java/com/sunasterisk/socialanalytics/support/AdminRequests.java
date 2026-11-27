package com.sunasterisk.socialanalytics.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Every MockMvc request runs as an {@code ADMIN} with a valid CSRF token, so tests of the API behaviour don't
 * repeat the login. Access rules themselves are tested without it ({@code SecurityApiTests}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class AdminRequests {

    public static final String USERNAME = "test-admin";

    @Bean
    MockMvcBuilderCustomizer adminRequests() {
        return builder -> builder.defaultRequest(get("/")
                .with(user(USERNAME).roles("ADMIN"))
                // The API only reads the token from the header (HeaderCsrfTokenRequestHandler).
                .with(csrf().asHeader()));
    }
}
