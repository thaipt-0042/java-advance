package com.sunasterisk.socialanalytics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.SocialMetricRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Two requests can both pass the duplicate check before either inserts. The check is stubbed to "not found" to
 * reproduce that race cheaply; the UNIQUE constraint must then still produce a 409 (and a log line), not a 500.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConcurrentWriteApiTests extends ApiTestBase {

    private static final String GENERIC_CONFLICT = "Dữ liệu xung đột với dữ liệu hiện có, hãy tải lại rồi thử lại";

    @MockitoSpyBean
    private PostRepository postRepository;

    @MockitoSpyBean
    private SocialMetricRepository metricRepository;

    @Test
    void duplicatePostThatPassedTheCheckIsRejectedByUniqueConstraint(CapturedOutput output) throws Exception {
        String externalId = "race-" + token();
        createPost("FACEBOOK", externalId, "page", "c");
        doReturn(false).when(postRepository).existsByPlatformAndExternalId(any(), any());

        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "FACEBOOK", "externalId": "%s", "accountName": "page"}
                        """.formatted(externalId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value(GENERIC_CONFLICT));
        assertThat(output).contains("Data integrity violation").contains("uk_posts_platform_external_id");
    }

    @Test
    void duplicateSnapshotThatPassedTheCheckIsRejectedOnCreateAndUpdate() throws Exception {
        long postId = createPost("TWITTER", "race-" + token(), "page", "c");
        createMetric(postId, "2026-03-01T00:00:00Z", 1);
        long other = createMetric(postId, "2026-03-02T00:00:00Z", 1);
        doReturn(false).when(metricRepository).existsByPostIdAndCapturedAt(any(), any());
        doReturn(false).when(metricRepository).existsByPostIdAndCapturedAtAndIdNot(any(), any(), any());

        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON)
                        .content(metricJson(postId, "2026-03-01T00:00:00Z", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(GENERIC_CONFLICT));
        mockMvc.perform(put("/api/v1/metrics/{id}", other).contentType(MediaType.APPLICATION_JSON).content("""
                        {"capturedAt": "2026-03-01T00:00:00Z", "likes": 1, "shares": 0, "comments": 0, "followers": 0,
                         "updatedAt": "%s"}
                        """.formatted(updatedAtOf("/api/v1/metrics", other))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(GENERIC_CONFLICT));
    }

    @Test
    void unexpectedErrorIsA500WithoutInternalDetails(CapturedOutput output) throws Exception {
        long id = createPost("FACEBOOK", "boom-" + token(), "page", "c");
        doThrow(new IllegalStateException("secret internal detail")).when(postRepository).findById(id);

        mockMvc.perform(get("/api/v1/posts/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Đã có lỗi xảy ra"));
        assertThat(output).contains("Unhandled exception").contains("secret internal detail");
    }
}
