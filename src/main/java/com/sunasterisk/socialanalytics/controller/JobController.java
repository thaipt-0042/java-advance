package com.sunasterisk.socialanalytics.controller;

import com.sunasterisk.socialanalytics.dto.LatestRunResponse;
import com.sunasterisk.socialanalytics.dto.RunStartedResponse;
import com.sunasterisk.socialanalytics.service.metrics.MetricUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sunasterisk.socialanalytics.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs/update-metrics")
@Tag(name = "Jobs", description = "Job cập nhật số liệu từ Facebook / X (giả lập), chạy mỗi giờ")
public class JobController {

    private final MetricUpdateService metricUpdateService;

    public JobController(MetricUpdateService metricUpdateService) {
        this.metricUpdateService = metricUpdateService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Chạy job ngay (ADMIN)",
            description = "Bắt đầu chạy nền rồi trả về ngay; theo dõi bằng `GET .../latest`.")
    @ApiResponse(responseCode = "202", description = "Đã bắt đầu")
    @ApiResponse(responseCode = "409", description = "Job đang chạy (ở instance này hoặc instance khác)")
    public RunStartedResponse start(Authentication authentication) {
        return new RunStartedResponse(metricUpdateService.startManual(CurrentUser.userId(authentication)));
    }

    @GetMapping("/latest")
    @Operation(summary = "Lần chạy gần nhất và thời điểm cập nhật cuối",
            description = "Chưa chạy lần nào thì mọi trường là null.")
    @ApiResponse(responseCode = "200", description = "OK")
    public LatestRunResponse latest() {
        return metricUpdateService.latest();
    }
}
