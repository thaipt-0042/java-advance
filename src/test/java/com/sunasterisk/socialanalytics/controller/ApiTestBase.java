package com.sunasterisk.socialanalytics.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sunasterisk.socialanalytics.support.AdminRequests;
import com.sunasterisk.socialanalytics.support.TestcontainersConfiguration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API tests through MockMvc on the full application and real MySQL. Deliberately not {@code @Transactional}:
 * each request commits like in production, so lazy loading and commit-time errors show up. The database is shared,
 * so every test creates its own rows with a random token and filters on it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AdminRequests.class})
@ActiveProfiles("test")
abstract class ApiTestBase {

    @Autowired
    protected MockMvc mockMvc;

    protected static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected long createPost(String platform, String externalId, String accountName, String content)
            throws Exception {
        String body = """
                {"platform": "%s", "externalId": "%s", "accountName": "%s", "content": "%s"}
                """.formatted(platform, externalId, accountName, content);
        String response = mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    protected long createMetric(long postId, String capturedAt, long likes) throws Exception {
        String response = mockMvc.perform(post("/api/v1/metrics").contentType(MediaType.APPLICATION_JSON)
                        .content(metricJson(postId, capturedAt, likes)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    /** The current updatedAt of a post or snapshot, which an update must send back. */
    protected String updatedAtOf(String path, long id) throws Exception {
        String response = mockMvc.perform(get(path + "/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.updatedAt");
    }

    protected static String metricJson(long postId, String capturedAt, long likes) {
        return """
                {"postId": %d, "capturedAt": "%s", "likes": %d, "shares": 2, "comments": 3, "followers": 100}
                """.formatted(postId, capturedAt, likes);
    }
}
