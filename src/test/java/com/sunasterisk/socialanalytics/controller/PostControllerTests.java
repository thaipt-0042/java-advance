package com.sunasterisk.socialanalytics.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
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

import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.dto.PostCreateRequest;
import com.sunasterisk.socialanalytics.dto.PostFilter;
import com.sunasterisk.socialanalytics.dto.PostResponse;
import com.sunasterisk.socialanalytics.dto.PostUpdateRequest;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.exception.ResourceNotFoundException;
import com.sunasterisk.socialanalytics.security.SecurityConfig;
import com.sunasterisk.socialanalytics.service.PostService;
import com.sunasterisk.socialanalytics.support.AdminRequests;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web layer only: request binding, validation, status codes and the error format, with {@link PostService}
 * mocked. The full stack on MySQL is covered by {@link PostApiTests}.
 */
@WebMvcTest(PostController.class)
@Import({SecurityConfig.class, AdminRequests.class})
class PostControllerTests {

    private static final Instant CREATED = Instant.parse("2026-03-01T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostService postService;

    @Test
    void listBindsFiltersAndPaging() throws Exception {
        when(postService.search(any(), any()))
                .thenReturn(new PageResponse<>(List.of(sample(7L)), 1, 5, 6, 2));

        mockMvc.perform(get("/api/v1/posts").param("platform", "FACEBOOK").param("q", "sun")
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));

        verify(postService).search(new PostFilter(SocialPlatform.FACEBOOK, "sun"), new PageQuery(1, 5));
    }

    @Test
    void listWithInvalidPagingReturns422WithoutCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/posts").param("size", "101").param("page", "-1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("page", "size")));

        verifyNoInteractions(postService);
    }

    @Test
    void getReturnsEveryField() throws Exception {
        when(postService.get(7L)).thenReturn(sample(7L));

        mockMvc.perform(get("/api/v1/posts/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.platform").value("FACEBOOK"))
                .andExpect(jsonPath("$.externalId").value("ext-7"))
                .andExpect(jsonPath("$.accountName").value("sun.asterisk"))
                .andExpect(jsonPath("$.url").value("https://facebook.com/p/7"))
                .andExpect(jsonPath("$.content").value("Xin chào"))
                .andExpect(jsonPath("$.publishedAt").value("2026-02-28T10:00:00Z"))
                .andExpect(jsonPath("$.createdAt").value("2026-03-01T03:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-03-01T03:00:00Z"));
    }

    @Test
    void getUnknownIdReturns404ProblemDetail() throws Exception {
        when(postService.get(99L)).thenThrow(new ResourceNotFoundException("bài viết", 99L));

        mockMvc.perform(get("/api/v1/posts/99"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Không tìm thấy bài viết có id = 99"))
                .andExpect(jsonPath("$.instance").value("/api/v1/posts/99"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(postService.create(any())).thenReturn(sample(7L));

        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "FACEBOOK", "externalId": "ext-7", "accountName": "sun.asterisk",
                         "url": "https://facebook.com/p/7", "content": "Xin chào",
                         "publishedAt": "2026-02-28T10:00:00Z"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/posts/7"))
                .andExpect(jsonPath("$.id").value(7));

        verify(postService).create(new PostCreateRequest(SocialPlatform.FACEBOOK, "ext-7", "sun.asterisk",
                "https://facebook.com/p/7", "Xin chào", Instant.parse("2026-02-28T10:00:00Z"), null));
    }

    @Test
    void createInvalidBodyReturns422WithFieldErrorsWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"externalId": " ", "accountName": "a", "url": "ftp://x"}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("platform", "externalId", "url")));

        verifyNoInteractions(postService);
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        when(postService.create(any())).thenThrow(new ConflictException("Bài viết FACEBOOK có externalId = x đã tồn tại"));

        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "FACEBOOK", "externalId": "x", "accountName": "a"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Bài viết FACEBOOK có externalId = x đã tồn tại"));
    }

    @Test
    void constraintViolationFromDatabaseReturns409WithoutInternalMessage() throws Exception {
        when(postService.create(any())).thenThrow(new DataIntegrityViolationException("Duplicate entry 'uk_posts'"));

        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"platform": "FACEBOOK", "externalId": "x", "accountName": "a"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Dữ liệu xung đột với dữ liệu hiện có, hãy tải lại rồi thử lại"));
    }

    @Test
    void updatePassesIdAndBody() throws Exception {
        when(postService.update(eq(7L), any())).thenReturn(sample(7L));

        mockMvc.perform(put("/api/v1/posts/7").contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "sun.asterisk", "content": "Xin chào", "updatedAt": "2026-03-01T03:00:00Z"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(postService).update(7L, new PostUpdateRequest("sun.asterisk", null, "Xin chào", null, null, CREATED));
    }

    @Test
    void updateWithoutUpdatedAtReturns422WithoutCallingService() throws Exception {
        mockMvc.perform(put("/api/v1/posts/7").contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "sun.asterisk"}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("updatedAt"));

        verifyNoInteractions(postService);
    }

    @Test
    void deleteReturns204AndUnknownIdReturns404() throws Exception {
        doThrow(new ResourceNotFoundException("bài viết", 99L)).when(postService).delete(99L);

        mockMvc.perform(delete("/api/v1/posts/7")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/posts/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(postService).delete(7L);
    }

    @Test
    void nonNumericIdReturns400InCommonFormat() throws Exception {
        mockMvc.perform(get("/api/v1/posts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(postService);
    }

    @Test
    void unexpectedErrorReturns500WithoutInternalMessage() throws Exception {
        when(postService.get(7L)).thenThrow(new IllegalStateException("connection pool exhausted"));

        mockMvc.perform(get("/api/v1/posts/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Đã có lỗi xảy ra"));
    }

    private static PostResponse sample(long id) {
        return new PostResponse(id, SocialPlatform.FACEBOOK, "ext-" + id, "sun.asterisk", "https://facebook.com/p/" + id,
                "Xin chào", Instant.parse("2026-02-28T10:00:00Z"), null, CREATED, CREATED);
    }
}
