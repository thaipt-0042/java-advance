package com.sunasterisk.socialanalytics.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SocialMetricApiTests extends ApiTestBase {

    @Test
    void createsSnapshotForPost() throws Exception {
        long postId = createPost("FACEBOOK", "m-" + token(), "page", "c");

        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON)
                        .content(metricJson(postId, "2026-03-01T03:00:00Z", 10)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/metrics/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.postId").value(postId))
                .andExpect(jsonPath("$.likes").value(10))
                .andExpect(jsonPath("$.shares").value(2))
                .andExpect(jsonPath("$.comments").value(3))
                .andExpect(jsonPath("$.followers").value(100))
                .andExpect(jsonPath("$.capturedAt").value("2026-03-01T03:00:00Z"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.length()").value(9));
    }

    @Test
    void returns404ForUnknownPostInBodyAndQuery() throws Exception {
        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON)
                        .content(metricJson(Long.MAX_VALUE, "2026-03-01T03:00:00Z", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/metrics").param("postId", String.valueOf(Long.MAX_VALUE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsNegativeAndMissingCountersWith422() throws Exception {
        long postId = createPost("FACEBOOK", "m-" + token(), "page", "c");

        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON).content("""
                        {"postId": %d, "capturedAt": "2026-03-01T03:00:00Z", "likes": -1, "shares": 0, "comments": 0}
                        """.formatted(postId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("likes", "followers")));
    }

    @Test
    void rejectsSecondSnapshotAtSameTimeWith409() throws Exception {
        long postId = createPost("TWITTER", "m-" + token(), "page", "c");
        createMetric(postId, "2026-03-01T03:00:00.123456789Z", 1);

        // Stored at microsecond precision (DATETIME(6)), so this is the same instant as above.
        mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON)
                        .content(metricJson(postId, "2026-03-01T03:00:00.123456Z", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value(containsString("đã có snapshot")));
    }

    @Test
    void listsSnapshotsOfPostInTimeRangeLatestFirst() throws Exception {
        long postId = createPost("FACEBOOK", "m-" + token(), "page", "c");
        long first = createMetric(postId, "2026-03-01T00:00:00Z", 1);
        long second = createMetric(postId, "2026-03-02T00:00:00Z", 2);
        long third = createMetric(postId, "2026-03-03T00:00:00Z", 3);

        mockMvc.perform(get("/api/v1/metrics").param("postId", String.valueOf(postId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains((int) third, (int) second, (int) first)));
        // [from, to): from is included, to is not.
        mockMvc.perform(get("/api/v1/metrics").param("postId", String.valueOf(postId))
                        .param("from", "2026-03-02T00:00:00Z").param("to", "2026-03-03T00:00:00Z"))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) second)));
    }

    @Test
    void rejectsTimeRangeWhereFromIsNotBeforeTo() throws Exception {
        mockMvc.perform(get("/api/v1/metrics")
                        .param("from", "2026-03-02T00:00:00Z").param("to", "2026-03-02T00:00:00Z"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("from phải trước to"));
    }

    @Test
    void updatesSnapshotAndRejectsMovingItOntoAnotherOne() throws Exception {
        long postId = createPost("FACEBOOK", "m-" + token(), "page", "c");
        long first = createMetric(postId, "2026-03-01T00:00:00Z", 1);
        createMetric(postId, "2026-03-02T00:00:00Z", 2);

        String updatedAt = mockMvc.perform(put("/api/v1/metrics/{id}", first).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capturedAt": "2026-03-01T01:00:00Z", "likes": 7, "shares": 0, "comments": 0,
                                 "followers": 9, "updatedAt": "%s"}
                                """.formatted(updatedAtOf("/api/v1/metrics", first))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likes").value(7))
                .andExpect(jsonPath("$.capturedAt").value("2026-03-01T01:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(put("/api/v1/metrics/{id}", first).contentType(MediaType.APPLICATION_JSON).content("""
                        {"capturedAt": "2026-03-02T00:00:00Z", "likes": 7, "shares": 0, "comments": 0, "followers": 9,
                         "updatedAt": "%s"}
                        """.formatted(JsonPath.<String>read(updatedAt, "$.updatedAt"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Bài viết id = %d đã có snapshot tại 2026-03-02T00:00:00Z"
                        .formatted(postId)));
    }

    @Test
    void updateWithStaleUpdatedAtReturns409AndKeepsTheEarlierChange() throws Exception {
        long postId = createPost("FACEBOOK", "m-" + token(), "page", "c");
        long id = createMetric(postId, "2026-03-01T00:00:00Z", 1);
        String readByBoth = updatedAtOf("/api/v1/metrics", id);

        mockMvc.perform(put("/api/v1/metrics/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"capturedAt": "2026-03-01T00:00:00Z", "likes": 50, "shares": 2, "comments": 3,
                         "followers": 100, "updatedAt": "%s"}
                        """.formatted(readByBoth)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/metrics/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"capturedAt": "2026-03-01T00:00:00Z", "likes": 1, "shares": 2, "comments": 3,
                         "followers": 999, "updatedAt": "%s"}
                        """.formatted(readByBoth)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Snapshot id = %d đã bị sửa sau khi bạn tải, hãy tải lại rồi thử lại".formatted(id)));

        mockMvc.perform(get("/api/v1/metrics/{id}", id))
                .andExpect(jsonPath("$.likes").value(50))
                .andExpect(jsonPath("$.followers").value(100));
    }

    @Test
    void validatesUpdateBodyWith422() throws Exception {
        long postId = createPost("FACEBOOK", "m-" + token(), "page", "c");
        long id = createMetric(postId, "2026-03-01T00:00:00Z", 1);

        mockMvc.perform(put("/api/v1/metrics/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"capturedAt": "2026-03-01T00:00:00Z", "likes": 1, "shares": -5, "comments": 0, "followers": 0}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("shares", "updatedAt")));
    }

    @Test
    void deletesSnapshotAndReturns404Afterwards() throws Exception {
        long postId = createPost("FACEBOOK", "m-" + token(), "page", "c");
        long id = createMetric(postId, "2026-03-01T00:00:00Z", 1);

        mockMvc.perform(delete("/api/v1/metrics/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/metrics/{id}", id)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/metrics/{id}", id)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/metrics/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"capturedAt": "2026-03-01T00:00:00Z", "likes": 1, "shares": 0, "comments": 0, "followers": 0,
                         "updatedAt": "2026-03-01T00:00:00Z"}
                        """))
                .andExpect(status().isNotFound());
    }
}
