package com.sunasterisk.socialanalytics.security;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.controller.PostController;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.dto.PostResponse;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.service.PostService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Access rules of both filter chains, with the demo login of the test profile ({@code application-test.properties})
 * and {@link PostService} mocked. No default user is set here, unlike the other controller tests.
 */
@WebMvcTest(controllers = PostController.class, properties = {
    "app.demo-login.enabled=true",
    "app.demo-login.admin-password=test-admin-password",
    "app.demo-login.user-password=test-user-password",
})
@Import(SecurityConfig.class)
class SecurityConfigTests {

    private static final String CREATE_POST = """
            {"platform": "FACEBOOK", "externalId": "1", "accountName": "page"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostService postService;

    @Test
    void apiWithoutLoginIs401InTheCommonErrorFormat() throws Exception {
        mockMvc.perform(get("/api/v1/posts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Cần đăng nhập"));
    }

    @Test
    void userCanReadButNotWrite() throws Exception {
        when(postService.search(any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/posts").with(user("viewer").roles("USER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/posts").with(user("viewer").roles("USER")).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_POST))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Không có quyền thực hiện thao tác này"));
        mockMvc.perform(delete("/api/v1/posts/1").with(user("viewer").roles("USER")).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanWriteWithTheCsrfHeader() throws Exception {
        when(postService.create(any())).thenReturn(new PostResponse(1L, SocialPlatform.FACEBOOK, "1", "page", null,
                null, null, Instant.parse("2026-03-01T03:00:00Z"), Instant.parse("2026-03-01T03:00:00Z")));

        mockMvc.perform(post("/api/v1/posts").with(user("boss").roles("ADMIN")).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_POST))
                .andExpect(status().isCreated());
    }

    @Test
    void apiWriteWithoutOrWithAWrongCsrfTokenIs403() throws Exception {
        mockMvc.perform(post("/api/v1/posts").with(user("boss").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_POST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value(containsString("CSRF")));
        mockMvc.perform(post("/api/v1/posts").with(user("boss").roles("ADMIN"))
                        .with(csrf().asHeader().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_POST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("CSRF")));
    }

    @Test
    void apiIgnoresATokenSentAsFormField() throws Exception {
        // The API reads the header only (HeaderCsrfTokenRequestHandler).
        mockMvc.perform(post("/api/v1/posts").with(user("boss").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_POST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("CSRF")));
    }

    @Test
    void undeclaredApiPathIsDenied() throws Exception {
        mockMvc.perform(get("/api/v2/posts").with(user("boss").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/internal").with(user("boss").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void webPagesRedirectToLoginAndUndeclaredPathsAreDenied() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/some/undeclared/page"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/some/undeclared/page").with(user("boss").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicPathsNeedNoLogin() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        // Not mapped in this slice, so 404 rather than a redirect to the login page.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }

    @Test
    void demoAccountsLogInWithTheirRole() throws Exception {
        mockMvc.perform(formLogin().user(DemoLoginProperties.ADMIN_USERNAME).password("test-admin-password"))
                .andExpect(authenticated().withUsername("demo-admin").withRoles("ADMIN"))
                .andExpect(redirectedUrl("/"));
        mockMvc.perform(formLogin().user(DemoLoginProperties.USER_USERNAME).password("test-user-password"))
                .andExpect(authenticated().withUsername("demo-user").withRoles("USER"));
        mockMvc.perform(formLogin().user(DemoLoginProperties.ADMIN_USERNAME).password("wrong-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void loginAndLogoutNeedTheCsrfToken() throws Exception {
        mockMvc.perform(post("/login").param("username", "demo-admin").param("password", "test-admin-password"))
                .andExpect(status().isForbidden())
                .andExpect(unauthenticated());
        mockMvc.perform(logout())
                .andExpect(redirectedUrl("/login?logout"));
        mockMvc.perform(post("/logout").with(user("boss").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }
}
