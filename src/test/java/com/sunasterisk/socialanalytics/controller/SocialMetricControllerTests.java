package com.sunasterisk.socialanalytics.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.dto.MetricCreateRequest;
import com.sunasterisk.socialanalytics.dto.MetricFilter;
import com.sunasterisk.socialanalytics.dto.MetricResponse;
import com.sunasterisk.socialanalytics.dto.MetricUpdateRequest;
import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.exception.ResourceNotFoundException;
import com.sunasterisk.socialanalytics.service.SocialMetricService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web layer only, with {@link SocialMetricService} mocked. The full stack on MySQL is covered by
 * {@link SocialMetricApiTests}.
 */
@WebMvcTest(SocialMetricController.class)
class SocialMetricControllerTests {

    private static final Instant CAPTURED = Instant.parse("2026-03-01T01:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-03-01T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SocialMetricService metricService;

    @Test
    void listBindsFiltersAndPaging() throws Exception {
        when(metricService.search(any(), any())).thenReturn(new PageResponse<>(List.of(sample(11L)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/metrics").param("postId", "5")
                        .param("from", "2026-03-01T00:00:00Z").param("to", "2026-03-08T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(11))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(metricService).search(new MetricFilter(5L, Instant.parse("2026-03-01T00:00:00Z"),
                Instant.parse("2026-03-08T00:00:00Z")), new PageQuery(null, null));
    }

    @Test
    void listWithFromNotBeforeToReturns422WithoutCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/metrics")
                        .param("from", "2026-03-08T00:00:00Z").param("to", "2026-03-08T00:00:00Z"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("rangeValid"))
                .andExpect(jsonPath("$.errors[0].message").value("from phải trước to"));

        verifyNoInteractions(metricService);
    }

    @Test
    void listWithUnparsableTimeReturns422WithGenericMessage() throws Exception {
        mockMvc.perform(get("/api/v1/metrics").param("from", "yesterday"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("Giá trị không hợp lệ"));

        verifyNoInteractions(metricService);
    }

    @Test
    void getReturnsEveryField() throws Exception {
        when(metricService.get(11L)).thenReturn(sample(11L));

        mockMvc.perform(get("/api/v1/metrics/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.postId").value(5))
                .andExpect(jsonPath("$.likes").value(120))
                .andExpect(jsonPath("$.shares").value(15))
                .andExpect(jsonPath("$.comments").value(8))
                .andExpect(jsonPath("$.followers").value(5400))
                .andExpect(jsonPath("$.capturedAt").value("2026-03-01T01:00:00Z"))
                .andExpect(jsonPath("$.createdAt").value("2026-03-01T03:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-03-01T03:00:00Z"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(metricService.create(any())).thenReturn(sample(11L));

        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON).content("""
                        {"postId": 5, "capturedAt": "2026-03-01T01:00:00Z",
                         "likes": 120, "shares": 15, "comments": 8, "followers": 5400}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/metrics/11"));

        verify(metricService).create(new MetricCreateRequest(5L, CAPTURED, 120L, 15L, 8L, 5400L));
    }

    @Test
    void createWithMissingOrNegativeCountersReturns422WithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON).content("""
                        {"postId": 5, "capturedAt": "2026-03-01T01:00:00Z", "likes": -1, "shares": 0, "comments": 0}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("likes", "followers")));

        verifyNoInteractions(metricService);
    }

    @Test
    void createForUnknownPostReturns404() throws Exception {
        when(metricService.create(any())).thenThrow(new ResourceNotFoundException("bài viết", 99L));

        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON).content("""
                        {"postId": 99, "capturedAt": "2026-03-01T01:00:00Z",
                         "likes": 0, "shares": 0, "comments": 0, "followers": 0}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy bài viết có id = 99"));
    }

    @Test
    void updateToTakenTimeReturns409() throws Exception {
        when(metricService.update(eq(11L), any()))
                .thenThrow(new ConflictException("Bài viết id = 5 đã có snapshot tại 2026-03-01T01:00:00Z"));

        mockMvc.perform(put("/api/v1/metrics/11").contentType(MediaType.APPLICATION_JSON).content("""
                        {"capturedAt": "2026-03-01T01:00:00Z", "likes": 1, "shares": 2, "comments": 3, "followers": 4,
                         "updatedAt": "2026-03-01T03:00:00Z"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        verify(metricService).update(11L, new MetricUpdateRequest(CAPTURED, 1L, 2L, 3L, 4L, CREATED));
    }

    @Test
    void deleteReturns204AndUnknownIdReturns404() throws Exception {
        doThrow(new ResourceNotFoundException("snapshot", 99L)).when(metricService).delete(99L);

        mockMvc.perform(delete("/api/v1/metrics/11")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/metrics/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy snapshot có id = 99"));

        verify(metricService).delete(11L);
    }

    private static MetricResponse sample(long id) {
        return new MetricResponse(id, 5L, 120, 15, 8, 5400, CAPTURED, CREATED, CREATED);
    }
}
