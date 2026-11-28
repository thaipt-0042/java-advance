package com.sunasterisk.socialanalytics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PostApiTests extends ApiTestBase {

    @Test
    void createsPostAndReturnsLocation() throws Exception {
        String externalId = "fb-" + token();
        String response = mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "FACEBOOK", "externalId": " %s ", "accountName": "sun.asterisk",
                         "url": "https://www.facebook.com/sun.asterisk/posts/1", "content": "Xin chào",
                         "publishedAt": "2026-03-01T03:00:00Z"}
                        """.formatted(externalId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(response, "$.id")).longValue();

        // Every field of PostResponse, on the create response and when read back.
        for (String body : new String[] {response,
                mockMvc.perform(get("/api/v1/posts/{id}", id)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString()}) {
            assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys("id", "platform",
                    "externalId", "accountName", "url", "content", "publishedAt", "adCost", "createdAt", "updatedAt");
            assertThat(JsonPath.<Number>read(body, "$.id").longValue()).isEqualTo(id);
            assertThat(JsonPath.<String>read(body, "$.platform")).isEqualTo("FACEBOOK");
            assertThat(JsonPath.<String>read(body, "$.externalId")).isEqualTo(externalId);
            assertThat(JsonPath.<String>read(body, "$.accountName")).isEqualTo("sun.asterisk");
            assertThat(JsonPath.<String>read(body, "$.url")).isEqualTo("https://www.facebook.com/sun.asterisk/posts/1");
            assertThat(JsonPath.<String>read(body, "$.content")).isEqualTo("Xin chào");
            assertThat(JsonPath.<String>read(body, "$.publishedAt")).isEqualTo("2026-03-01T03:00:00Z");
            assertThat(JsonPath.<String>read(body, "$.createdAt")).isNotBlank()
                    .isEqualTo(JsonPath.<String>read(response, "$.createdAt"));
            assertThat(JsonPath.<String>read(body, "$.updatedAt")).isEqualTo(JsonPath.<String>read(body, "$.createdAt"));
        }
    }

    @Test
    void locationHeaderPointsToTheNewPost() throws Exception {
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "TWITTER", "externalId": "tw-%s", "accountName": "sun"}
                        """.formatted(token())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/posts/")));
    }

    @Test
    void rejectsDuplicateExternalIdOnSamePlatformWith409() throws Exception {
        String externalId = "dup-" + token();
        createPost("FACEBOOK", externalId, "page", "a");

        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "FACEBOOK", "externalId": "%s", "accountName": "page"}
                        """.formatted(externalId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value(containsString(externalId)));
        // Same external id on another platform is a different post.
        createPost("TWITTER", externalId, "page", "a");
    }

    @Test
    void rejectsInvalidBodyWith422AndFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"externalId": " ", "accountName": "page", "url": "ftp://example.com/x", "content": "%s"}
                        """.formatted("x".repeat(5001))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("platform", "externalId", "url", "content")));
    }

    @Test
    void rejectsUnknownPlatformInBodyAsUnreadableJson() throws Exception {
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "INSTAGRAM", "externalId": "x", "accountName": "page"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void updatesEditableFieldsAndReturnsNewUpdatedAt() throws Exception {
        long id = createPost("FACEBOOK", "upd-" + token(), "old.page", "old");

        String updated = mockMvc.perform(put("/api/v1/posts/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountName": "new.page", "content": "new", "url": "",
                                 "publishedAt": "2026-03-01T03:00:00.123456789Z", "updatedAt": "%s"}
                                """.formatted(updatedAtOf("/api/v1/posts", id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("new.page"))
                .andExpect(jsonPath("$.content").value("new"))
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.publishedAt").value("2026-03-01T03:00:00.123456Z"))
                .andReturn().getResponse().getContentAsString();
        String createdAt = JsonPath.read(updated, "$.createdAt");
        String updatedAt = JsonPath.read(updated, "$.updatedAt");

        mockMvc.perform(get("/api/v1/posts/{id}", id))
                .andExpect(jsonPath("$.updatedAt").value(updatedAt))
                .andExpect(jsonPath("$.publishedAt").value("2026-03-01T03:00:00.123456Z"))
                .andExpect(jsonPath("$.updatedAt").value(not(createdAt)));
    }

    @Test
    void adCostIsKeptToTheCentAndAnUpdateWithoutItClearsIt() throws Exception {
        String externalId = "ad-" + token();
        String created = mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "FACEBOOK", "externalId": "%s", "accountName": "page", "adCost": 12.5}
                        """.formatted(externalId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        mockMvc.perform(get("/api/v1/posts/{id}", id)).andExpect(jsonPath("$.adCost").value(12.5));

        mockMvc.perform(put("/api/v1/posts/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "page", "adCost": 1234567890123.45, "updatedAt": "%s"}
                        """.formatted(updatedAtOf("/api/v1/posts", id))))
                .andExpect(status().isOk());
        // Checked as text: a double can't hold 15 significant digits exactly.
        mockMvc.perform(get("/api/v1/posts/{id}", id))
                .andExpect(content().string(containsString("\"adCost\":1234567890123.45,")));

        // PUT replaces every editable field: no adCost means none.
        mockMvc.perform(put("/api/v1/posts/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "page", "updatedAt": "%s"}
                        """.formatted(updatedAtOf("/api/v1/posts", id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adCost").doesNotExist());
    }

    @Test
    void rejectsANegativeAdCostOrOneWithMoreThanTwoDecimals() throws Exception {
        for (String adCost : new String[] {"-0.01", "1.234", "12345678901234"}) {
            mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                            {"platform": "FACEBOOK", "externalId": "ad-%s", "accountName": "page", "adCost": %s}
                            """.formatted(token(), adCost)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("adCost"));
        }
    }

    @Test
    void updateWithStaleUpdatedAtReturns409AndKeepsTheEarlierChange() throws Exception {
        long id = createPost("FACEBOOK", "upd-" + token(), "page", "c");
        String readByBoth = updatedAtOf("/api/v1/posts", id);

        mockMvc.perform(put("/api/v1/posts/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "changed.by.a", "content": "c", "updatedAt": "%s"}
                        """.formatted(readByBoth)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/posts/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "page", "content": "changed by b", "updatedAt": "%s"}
                        """.formatted(readByBoth)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail")
                        .value("Bài viết id = %d đã bị sửa sau khi bạn tải, hãy tải lại rồi thử lại".formatted(id)));

        mockMvc.perform(get("/api/v1/posts/{id}", id))
                .andExpect(jsonPath("$.accountName").value("changed.by.a"))
                .andExpect(jsonPath("$.content").value("c"));
    }

    @Test
    void validatesUpdateBodyWith422() throws Exception {
        long id = createPost("FACEBOOK", "upd-" + token(), "page", "c");

        mockMvc.perform(put("/api/v1/posts/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": ""}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("accountName", "updatedAt")));
    }

    @Test
    void returns404ForUnknownIdOnGetUpdateAndDelete() throws Exception {
        long missing = Long.MAX_VALUE;
        mockMvc.perform(get("/api/v1/posts/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/posts/" + missing));
        mockMvc.perform(put("/api/v1/posts/{id}", missing).contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "page", "updatedAt": "2026-03-01T00:00:00Z"}
                        """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/posts/{id}", missing))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesPostTogetherWithItsMetrics() throws Exception {
        long postId = createPost("TWITTER", "del-" + token(), "page", "c");
        long metricId = createMetric(postId, "2026-03-01T03:00:00Z", 5);

        mockMvc.perform(delete("/api/v1/posts/{id}", postId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/posts/{id}", postId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/metrics/{id}", metricId)).andExpect(status().isNotFound());
    }

    @Test
    void filtersByPlatformAndKeyword() throws Exception {
        String token = token();
        long facebook = createPost("FACEBOOK", "f-" + token, "page", "Khuyến mãi " + token);
        long twitter = createPost("TWITTER", "t-" + token, "page", "Khuyến mãi " + token);

        mockMvc.perform(get("/api/v1/posts").param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                // newest first
                .andExpect(jsonPath("$.content[0].id").value(twitter))
                .andExpect(jsonPath("$.content[1].id").value(facebook));
        mockMvc.perform(get("/api/v1/posts").param("q", token).param("platform", "FACEBOOK"))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder((int) facebook)));
    }

    @Test
    void searchesAccountNameCaseAndAccentInsensitively() throws Exception {
        String token = token();
        long id = createPost("FACEBOOK", "a-" + token, "Trí Thái " + token, "x");

        mockMvc.perform(get("/api/v1/posts").param("q", "tri thai " + token))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder((int) id)));
    }

    @Test
    void treatsPercentAndUnderscoreInKeywordLiterally() throws Exception {
        String token = token();
        long percent = createPost("FACEBOOK", "p1-" + token, "page", "Giảm 50% " + token);
        createPost("FACEBOOK", "p2-" + token, "page", "Giảm 500 " + token);
        long underscore = createPost("FACEBOOK", "u1-" + token, "a_b" + token, "x");
        createPost("FACEBOOK", "u2-" + token, "axb" + token, "x");

        mockMvc.perform(get("/api/v1/posts").param("q", "50% " + token))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder((int) percent)));
        mockMvc.perform(get("/api/v1/posts").param("q", "a_b" + token))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder((int) underscore)));
    }

    @Test
    void returnsEmptyPageBeyondTheLastOne() throws Exception {
        String token = token();
        createPost("FACEBOOK", "pg1-" + token, "page", token);
        createPost("FACEBOOK", "pg2-" + token, "page", token);

        mockMvc.perform(get("/api/v1/posts").param("q", token).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/posts").param("q", token).param("size", "1").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(5))
                .andExpect(jsonPath("$.totalElements").value(2));
        // Largest allowed page with the largest size: offset still fits in an int.
        mockMvc.perform(get("/api/v1/posts").param("page", "10000000").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void rejectsInvalidQueryParametersWith422() throws Exception {
        mockMvc.perform(get("/api/v1/posts").param("page", "-1").param("size", "101"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("page", "size")));
        mockMvc.perform(get("/api/v1/posts").param("platform", "INSTAGRAM"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("platform"))
                .andExpect(jsonPath("$.errors[0].message").value("Giá trị không hợp lệ"));
        mockMvc.perform(get("/api/v1/posts").param("page", "10000001"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void keepsFrameworkStatusesInTheCommonErrorFormat() throws Exception {
        mockMvc.perform(get("/api/v1/posts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mockMvc.perform(delete("/api/v1/posts"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value(endsWith("/api/v1/does-not-exist")));
    }
}
