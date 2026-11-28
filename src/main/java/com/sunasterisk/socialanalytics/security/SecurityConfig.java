package com.sunasterisk.socialanalytics.security;

import com.sunasterisk.socialanalytics.service.UserAccountService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Three filter chains ({@code docs/03-kien-truc.md}, section 3):
 *
 * <ul>
 *   <li><b>API</b> ({@code /api/**}): any logged-in user may read ({@code GET}); every other method needs
 *       {@code ADMIN} ({@code docs/cau-hoi-lam-ro.md} Q1). 401 / 403 are JSON in the common error format, never a
 *       redirect.</li>
 *   <li><b>SOAP</b> ({@code /soap/**}): the mock exchange-rate service, public, no CSRF (#15).</li>
 *   <li><b>Web</b> (every other path): the login page, static files, health and Swagger are public; the dashboard
 *       and the WebSocket endpoint need a login, its "run the job now" button {@code ADMIN}; <b>any other path is
 *       denied</b>, so a path nobody declared is never open by accident.
 *       Not logged in: redirect to {@code /login}, which offers the configured Facebook / X logins and, in dev, the
 *       demo form (#9).</li>
 * </ul>
 *
 * <p>CSRF is on for both. The token lives in the {@code XSRF-TOKEN} cookie (readable by JavaScript). The API only
 * accepts it back raw in the {@code X-XSRF-TOKEN} header ({@link HeaderCsrfTokenRequestHandler}); the web chain
 * also accepts it masked in the {@code _csrf} field of a server-rendered form ({@code csrf.spa()}).
 *
 * <p>Authorization rules are on URLs only, not on methods: an {@code AccessDeniedException} thrown inside a
 * controller would reach the controller advice instead of these handlers.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({DemoLoginProperties.class, SocialLoginProperties.class})
public class SecurityConfig {

    static final String LOGIN_PAGE = "/login";

    static final String[] PUBLIC_WEB_PATHS = {
        "/login", "/oauth2/authorization/*", "/login/oauth2/code/*", "/error", "/css/**", "/js/**", "/webjars/**",
        "/favicon.ico",
        "/actuator/health", "/actuator/health/**",
        // Only served in the dev profile (springdoc is off elsewhere); the API itself still needs a login.
        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
    };

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, AuthenticationEntryPoint problemEntryPoint,
            AccessDeniedHandler problemAccessDeniedHandler) {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
                        .requestMatchers("/api/v1/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new HeaderCsrfTokenRequestHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemEntryPoint)
                        .accessDeniedHandler(problemAccessDeniedHandler))
                // A 401 on the API must not make the next login redirect to an API URL.
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /**
     * The mock SOAP service: public and read-only, called by machines (no cookie, no session), so no CSRF
     * ({@code docs/03-kien-truc.md}, section 3).
     */
    @Bean
    @Order(2)
    SecurityFilterChain soapSecurityFilterChain(HttpSecurity http) {
        http.securityMatcher("/soap/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http, DemoLoginProperties demoLogin,
            SocialLoginProperties socialLogin, ObjectProvider<JdbcTemplate> jdbcTemplate,
            ObjectProvider<UserAccountService> userAccountService) {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_WEB_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/dashboard/update-metrics").hasRole("ADMIN")
                        .requestMatchers("/", "/dashboard", "/dashboard/run-status", "/ws/**").authenticated()
                        .anyRequest().denyAll())
                // SockJS can't add the CSRF header to its XHR / iframe posts: no HTTP-level check for /ws/** only;
                // the STOMP CONNECT frame carries the token instead (WebSocketSecurityConfig).
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/ws/**"))
                // SockJS's iframe transport loads a page of this origin in a frame.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                // Our own /login page (provider buttons, and the demo form when on): not logged in → redirect there,
                // even when no login method is configured (the page then says so).
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE)));
        if (demoLogin.enabled()) {
            http.formLogin(form -> form.loginPage(LOGIN_PAGE)
                    .defaultSuccessUrl("/dashboard", false)
                    .failureUrl(LOGIN_PAGE + "?error"));
        }
        List<ClientRegistration> registrations = SocialClientRegistrations.of(socialLogin);
        if (!registrations.isEmpty()) {
            ClientRegistrationRepository repository = new InMemoryClientRegistrationRepository(registrations);
            TokenCipher cipher = TokenCipher.fromBase64Key(socialLogin.tokenEncryptionKey());
            http.oauth2Login(oauth2 -> oauth2.loginPage(LOGIN_PAGE)
                    .clientRegistrationRepository(repository)
                    .authorizedClientService(
                            EncryptedTokens.authorizedClientService(jdbcTemplate.getObject(), repository, cipher))
                    .userInfoEndpoint(userInfo -> userInfo.userService(new SocialOAuth2UserService(
                            SocialClientRegistrations.userInfoClient(), userAccountService.getObject(),
                            socialLogin.admins())))
                    .defaultSuccessUrl("/dashboard", false)
                    .failureUrl(LOGIN_PAGE + "?error"));
        }
        return http.build();
    }

    /**
     * The demo accounts when enabled; otherwise no account at all. Declaring this bean (even empty) also stops
     * Spring Boot from creating its default {@code user} with a generated password.
     */
    @Bean
    UserDetailsService userDetailsService(DemoLoginProperties demoLogin, PasswordEncoder passwordEncoder) {
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        if (demoLogin.enabled()) {
            users.createUser(User.withUsername(DemoLoginProperties.ADMIN_USERNAME)
                    .password(passwordEncoder.encode(demoLogin.adminPassword()))
                    .roles("ADMIN")
                    .build());
            users.createUser(User.withUsername(DemoLoginProperties.USER_USERNAME)
                    .password(passwordEncoder.encode(demoLogin.userPassword()))
                    .roles("USER")
                    .build());
        }
        return users;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Not logged in on the API: 401 through {@code GlobalExceptionHandler}, like every other API error. */
    @Bean
    AuthenticationEntryPoint problemEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, ex) -> resolver.resolveException(request, response, null, ex);
    }

    /** Logged in without the role, or a missing / wrong CSRF token on the API: 403, same format. */
    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, ex) -> resolver.resolveException(request, response, null, ex);
    }
}
