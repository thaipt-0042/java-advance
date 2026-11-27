package com.sunasterisk.socialanalytics.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.excel.ExcelExport;
import com.sunasterisk.socialanalytics.exception.UnprocessableException;
import com.sunasterisk.socialanalytics.service.ExportedReport;
import com.sunasterisk.socialanalytics.service.ReportService;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web layer of the export with {@link ReportService} mocked; the file itself is covered by {@link ReportApiTests}. */
@WebMvcTest(ReportController.class)
class ReportControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @Test
    void tooManyRowsIs422WithoutFieldErrors() throws Exception {
        when(reportService.export(any())).thenThrow(new UnprocessableException("Có 100001 bài viết khớp bộ lọc"));

        mockMvc.perform(get("/api/v1/export-report"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.detail").value("Có 100001 bài viết khớp bộ lọc"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void closesTheReportOnceWritten() throws Exception {
        ExcelExport file = mock(ExcelExport.class);
        when(reportService.export(any())).thenReturn(new ExportedReport("social-report-2026-03-01.xlsx", file));

        mockMvc.perform(get("/api/v1/export-report"))
                .andExpect(status().isOk());

        verify(file).write(any());
        verify(file).close();
    }

    @Test
    void closesTheReportWhenWritingFails() throws Exception {
        ExcelExport file = mock(ExcelExport.class);
        doThrow(new IOException("client went away")).when(file).write(any());
        when(reportService.export(any())).thenReturn(new ExportedReport("social-report-2026-03-01.xlsx", file));

        mockMvc.perform(get("/api/v1/export-report"));

        verify(file).close();
    }
}
