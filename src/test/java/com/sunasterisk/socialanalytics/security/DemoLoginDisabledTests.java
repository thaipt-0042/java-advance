package com.sunasterisk.socialanalytics.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.controller.PostController;
import com.sunasterisk.socialanalytics.service.PostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Outside the dev profile: no login method until #9, and no account at all (not even Spring Boot's default). */
@WebMvcTest(controllers = PostController.class, properties = "app.demo-login.enabled=false")
@Import(SecurityConfig.class)
class DemoLoginDisabledTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserDetailsService userDetailsService;

    @MockitoBean
    private PostService postService;

    @Test
    void noLoginPageAndPagesAreForbidden() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isNotFound());
        mockMvc.perform(post("/login").param("username", "demo-admin").param("password", "x"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/posts")).andExpect(status().isUnauthorized());
    }

    @Test
    void noDemoAccountAndNotSpringBootsDefaultUser() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("demo-admin"))
                .isInstanceOf(UsernameNotFoundException.class);
        // Spring Boot would create "user" with a generated password if no UserDetailsService were declared.
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
