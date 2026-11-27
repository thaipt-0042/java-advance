package com.sunasterisk.socialanalytics.controller;

import com.sunasterisk.socialanalytics.dto.ChartDataFilter;
import com.sunasterisk.socialanalytics.dto.ChartDataResponse;
import com.sunasterisk.socialanalytics.service.ChartDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chart-data")
@Tag(name = "Charts", description = "Thống kê theo ngày cho biểu đồ")
public class ChartDataController {

    private final ChartDataService chartDataService;

    public ChartDataController(ChartDataService chartDataService) {
        this.chartDataService = chartDataService;
    }

    @GetMapping
    @Operation(summary = "Dữ liệu biểu đồ theo ngày và nền tảng",
            description = """
                    Mỗi mảng dài bằng `dates`. Mỗi số là trạng thái tính đến hết ngày đó; ngày không có dữ liệu \
                    giữ số của ngày gần nhất trước đó với `postsMeasured` = 0, trước lần đo đầu tiên thì là 0. \
                    Mặc định 7 ngày gần nhất, tối đa 366 ngày.""")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "422", description = "`from` sau `to`, khoảng quá 366 ngày, hoặc tham số sai kiểu")
    public ChartDataResponse chartData(@ParameterObject @Valid ChartDataFilter filter) {
        return chartDataService.chartData(filter);
    }
}
