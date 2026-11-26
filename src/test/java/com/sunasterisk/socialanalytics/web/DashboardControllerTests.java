package com.sunasterisk.socialanalytics.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.dto.LatestRunResponse;
import com.sunasterisk.socialanalytics.entity.RunStatus;
import com.sunasterisk.socialanalytics.entity.RunTrigger;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.security.SecurityConfig;
import com.sunasterisk.socialanalytics.service.metrics.MetricUpdateService;
import com.sunasterisk.socialanalytics.support.FixedClockConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The dashboard page with the real security rules and templates; the job service is mocked. */
@WebMvcTest(controllers = DashboardController.class, properties = {
    "app.demo-login.enabled=true",
    "app.demo-login.admin-password=test-admin-password",
    "app.demo-login.user-password=test-user-password",
})
@Import({SecurityConfig.class, FixedClockConfiguration.class})
class DashboardControllerTests {

    private static final LatestRunResponse PARTIAL_RUN = new LatestRunResponse(12L, RunStatus.PARTIAL,
            RunTrigger.SCHEDULED, Instant.parse("2026-11-20T03:00:00Z"), Instant.parse("2026-11-20T03:00:05Z"), 3, 1,
            4, "1/3 tài khoản lỗi: TWITTER/page (timeout)", Instant.parse("2026-11-20T03:00:05Z"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MetricUpdateService metricUpdateService;

    @Test
    void showsTheLastUpdateAndTheLatestRunInVietnamTime() throws Exception {
        when(metricUpdateService.latest()).thenReturn(PARTIAL_RUN);

        mockMvc.perform(get("/dashboard").with(user("viewer").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        // 03:00:05 UTC is 10:00:05 in Asia/Ho_Chi_Minh
                        containsString("<strong>20/11/2026 10:00:05</strong>"),
                        containsString("(giờ Asia/Ho_Chi_Minh)"),
                        containsString("#12"),
                        containsString("Thành công một phần"),
                        containsString("theo lịch"),
                        containsString("3 tài khoản, 1 lỗi, 4 snapshot"),
                        containsString("1/3 tài khoản lỗi: TWITTER/page (timeout)"),
                        containsString("viewer"))));
    }

    @Test
    void beforeTheFirstRun() throws Exception {
        when(metricUpdateService.latest()).thenReturn(LatestRunResponse.of(null, null));

        mockMvc.perform(get("/dashboard").with(user("viewer").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Chưa có lần cập nhật nào"),
                        containsString("Job chưa chạy lần nào"))));
    }

    @Test
    void onlyAdminsSeeTheRunButtonWhichCarriesTheCsrfField() throws Exception {
        when(metricUpdateService.latest()).thenReturn(PARTIAL_RUN);

        mockMvc.perform(get("/dashboard").with(user("viewer").roles("USER")))
                .andExpect(content().string(not(containsString("Chạy job ngay"))));
        mockMvc.perform(get("/dashboard").with(user("boss").roles("ADMIN")))
                .andExpect(content().string(allOf(containsString("Chạy job ngay"),
                        containsString("action=\"/dashboard/update-metrics\""),
                        containsString("name=\"_csrf\""))));
    }

    @Test
    void runButtonStartsTheJobAndComesBackWithAMessage() throws Exception {
        when(metricUpdateService.startManual()).thenReturn(13L);

        mockMvc.perform(post("/dashboard/update-metrics").with(user("boss").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(flash().attribute("message", DashboardController.STARTED.formatted(13L)));
    }

    @Test
    void runButtonWhileTheJobRunsSaysSo() throws Exception {
        when(metricUpdateService.startManual()).thenThrow(new ConflictException("running"));

        mockMvc.perform(post("/dashboard/update-metrics").with(user("boss").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(flash().attribute("error", DashboardController.ALREADY_RUNNING));
    }

    @Test
    void runButtonNeedsAdminAndTheCsrfField() throws Exception {
        mockMvc.perform(post("/dashboard/update-metrics").with(user("viewer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/dashboard/update-metrics").with(user("boss").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verify(metricUpdateService, never()).startManual();
    }

    @Test
    void homeAndDashboardNeedALogin() throws Exception {
        mockMvc.perform(get("/dashboard")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/").with(user("viewer").roles("USER"))).andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void anUnexpectedErrorShowsTheErrorPageNotJson() throws Exception {
        when(metricUpdateService.latest()).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/dashboard").with(user("viewer").roles("USER")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Đã có lỗi xảy ra")));
    }
}
