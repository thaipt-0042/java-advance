package com.sunasterisk.socialanalytics.controller;

import com.sunasterisk.socialanalytics.dto.ExchangeRateQuery;
import com.sunasterisk.socialanalytics.service.ExchangeRateService;
import com.sunasterisk.socialanalytics.service.ExchangeRateService.ExchangeRate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@Tag(name = "Exchange rates", description = "Tỷ giá lấy từ dịch vụ SOAP (giả lập)")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;
    private final Clock clock;

    public ExchangeRateController(ExchangeRateService exchangeRateService, Clock clock) {
        this.exchangeRateService = exchangeRateService;
        this.clock = clock;
    }

    @GetMapping("/usd-vnd")
    @Operation(summary = "Tỷ giá USD → VND của một ngày", description = "Mặc định hôm nay (giờ Việt Nam).")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "422", description = "Ngày không hợp lệ")
    @ApiResponse(responseCode = "502", description = "Dịch vụ tỷ giá lỗi (SOAP fault), quá thời gian hoặc không gọi được")
    public ExchangeRate usdToVnd(@ParameterObject ExchangeRateQuery query) {
        return exchangeRateService.usdToVnd(query.date() != null ? query.date() : LocalDate.now(clock));
    }
}
