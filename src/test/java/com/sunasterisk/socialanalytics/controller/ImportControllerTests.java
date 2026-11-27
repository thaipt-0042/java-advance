package com.sunasterisk.socialanalytics.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.dto.ImportResultResponse;
import com.sunasterisk.socialanalytics.exception.InvalidFileException;
import com.sunasterisk.socialanalytics.exception.RowError;
import com.sunasterisk.socialanalytics.exception.RowValidationException;
import com.sunasterisk.socialanalytics.security.SecurityConfig;
import com.sunasterisk.socialanalytics.service.PostImportService;
import com.sunasterisk.socialanalytics.support.AdminRequests;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web layer of the import: multipart binding, file name handling and the error format, with
 * {@link PostImportService} mocked. The full stack on MySQL is covered by {@link ImportApiTests}.
 */
@WebMvcTest(ImportController.class)
@Import({SecurityConfig.class, AdminRequests.class})
class ImportControllerTests {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostImportService importService;

    @Test
    void returnsImportResult() throws Exception {
        when(importService.importPosts(eq("posts.xlsx"), any())).thenReturn(
                new ImportResultResponse(5L, 3, 2, 1, 1, Instant.parse("2026-03-05T03:00:00.123456Z")));

        mockMvc.perform(multipart("/api/v1/import-posts").file(file("posts.xlsx", new byte[] {1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(5))
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.createdPosts").value(2))
                .andExpect(jsonPath("$.updatedPosts").value(1))
                .andExpect(jsonPath("$.snapshotsCreated").value(1))
                .andExpect(jsonPath("$.capturedAt").value("2026-03-05T03:00:00.123456Z"))
                .andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void keepsOnlyTheFileNameWithoutClientPathAndCutsItToTheColumnLength() throws Exception {
        mockMvc.perform(multipart("/api/v1/import-posts").file(file("C:\\Users\\me\\posts.xlsx", new byte[] {1})))
                .andExpect(status().isOk());
        verify(importService).importPosts(eq("posts.xlsx"), any());

        String longName = "a".repeat(300) + ".xlsx";
        mockMvc.perform(multipart("/api/v1/import-posts").file(file("../../" + longName, new byte[] {1})))
                .andExpect(status().isOk());
        verify(importService).importPosts(eq(longName.substring(0, 255)), any());

        mockMvc.perform(multipart("/api/v1/import-posts").file(file("", new byte[] {1})))
                .andExpect(status().isOk());
        verify(importService).importPosts(eq("(không tên)"), any());
    }

    @Test
    void rowErrorsReturn422WithRowAndColumn() throws Exception {
        when(importService.importPosts(any(), any())).thenThrow(new RowValidationException(
                "File có 2 lỗi, chưa có dòng nào được lưu",
                List.of(new RowError(7, null, "row rule"), new RowError(3, "likes", "Phải là số nguyên"))));

        mockMvc.perform(multipart("/api/v1/import-posts").file(file("posts.xlsx", new byte[] {1})))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.detail").value("File có 2 lỗi, chưa có dòng nào được lưu"))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].column").value("likes"))
                .andExpect(jsonPath("$.errors[0].message").value("Phải là số nguyên"))
                .andExpect(jsonPath("$.errors[1].row").value(7))
                .andExpect(jsonPath("$.errors[1].column").value(nullValue()));
    }

    @Test
    void invalidFileReturns400() throws Exception {
        when(importService.importPosts(any(), any()))
                .thenThrow(new InvalidFileException("File không phải file Excel .xlsx đọc được"));

        mockMvc.perform(multipart("/api/v1/import-posts").file(file("posts.csv", new byte[] {1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.detail").value("File không phải file Excel .xlsx đọc được"));
    }

    @Test
    void emptyOrMissingFileOrWrongContentTypeIsRejectedWithoutCallingService() throws Exception {
        mockMvc.perform(multipart("/api/v1/import-posts").file(file("posts.xlsx", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("File rỗng"));
        mockMvc.perform(multipart("/api/v1/import-posts").file(new MockMultipartFile("other", new byte[] {1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mockMvc.perform(post("/api/v1/import-posts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(importService);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, XLSX, content);
    }
}
