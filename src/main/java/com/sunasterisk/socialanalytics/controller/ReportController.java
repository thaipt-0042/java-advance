package com.sunasterisk.socialanalytics.controller;

import com.sunasterisk.socialanalytics.dto.ReportFilter;
import com.sunasterisk.socialanalytics.service.ExportedReport;
import com.sunasterisk.socialanalytics.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/export-report")
@Tag(name = "Report", description = "Xuất báo cáo Excel")
public class ReportController {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @Operation(summary = "Xuất bài viết ra file .xlsx",
            description = """
                    Sheet "Posts": mỗi bài viết khớp bộ lọc kèm snapshot mới nhất (cột số liệu trống nếu bài chưa \
                    có snapshot), thời gian theo giờ Việt Nam. `from` / `to` lọc theo ngày đăng, bao gồm cả hai \
                    đầu, tối đa 366 ngày; có `from` hoặc `to` thì bài không có ngày đăng bị bỏ. Tối đa 100.000 bài.""")
    @ApiResponse(responseCode = "200", description = "File .xlsx, tên `social-report-<ngày xuất>.xlsx`",
            content = @Content(mediaType = XLSX))
    @ApiResponse(responseCode = "422", description = "Ngày không đúng dạng yyyy-MM-dd, nền tảng lạ, `from` sau `to`, "
            + "khoảng quá 366 ngày, hoặc quá 100.000 bài")
    public void export(@ParameterObject @Valid ReportFilter filter, HttpServletResponse response)
            throws IOException {
        try (ExportedReport report = reportService.export(filter)) {
            response.setContentType(XLSX);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(report.fileName()).build().toString());
            report.write(response.getOutputStream());
        }
    }
}
