package com.sunasterisk.socialanalytics.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Two filter chains ({@code docs/03-kien-truc.md}, section 3):
 *
 * <ul>
 *   <li><b>API</b> ({@code /api/**}): any logged-in user may read ({@code GET}); every other method needs
 *       {@code ADMIN} ({@code docs/cau-hoi-lam-ro.md} Q1). 401 / 403 are JSON in the common error format, never a
 *       redirect.</li>
 *   <li><b>Web</b> (every other path): the login page, static files, health and Swagger are public; the dashboard
 *       needs a login, its "run the job now" button {@code ADMIN}; <b>any other path is denied</b>, so a path nobody declared is never open by accident.
 *       Not logged in: redirect to {@code /login} when the demo login is on; 403 while there is no login method.</li>
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
@EnableConfigurationProperties(DemoLoginProperties.class)
public class SecurityConfig {

    static final String[] PUBLIC_WEB_PATHS = {
        "/login", "/error", "/css/**", "/js/**", "/favicon.ico", "/actuator/health", "/actuator/health/**",
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

    @Bean
    @Order(2)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http, DemoLoginProperties demoLogin) {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_WEB_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/dashboard/update-metrics").hasRole("ADMIN")
                        .requestMatchers("/", "/dashboard").authenticated()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.spa())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        if (demoLogin.enabled()) {
            // Spring Security's generated login page (with the CSRF field); not logged in → redirect to it. Setting
            // an entry point by hand would turn the generated page off. Without any login method, not logged in →
            // 403 (OAuth2 Login comes with #9).
            http.formLogin(form -> form.defaultSuccessUrl("/", false));
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
