package com.sunasterisk.socialanalytics.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunasterisk.socialanalytics.dto.ChartDataFilter;
import com.sunasterisk.socialanalytics.dto.ChartDataResponse;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.security.SecurityConfig;
import com.sunasterisk.socialanalytics.service.ChartDataService;
import com.sunasterisk.socialanalytics.support.AdminRequests;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChartDataController.class)
@Import({SecurityConfig.class, AdminRequests.class})
class ChartDataControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChartDataService chartDataService;

    @Test
    void passesTheFilterAndReturnsTheSeries() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 20);
        when(chartDataService.chartData(any())).thenReturn(new ChartDataResponse(day, day, List.of(day),
                List.of(new ChartDataResponse.Series(SocialPlatform.TWITTER, List.of(4), List.of(3), List.of(1350L),
                        List.of(92L), List.of(41L), List.of(25060L))), null));

        mockMvc.perform(get("/api/v1/chart-data").param("from", "2026-11-20").param("to", "2026-11-20")
                        .param("platform", "TWITTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-11-20"))
                .andExpect(jsonPath("$.series[0].platform").value("TWITTER"))
                .andExpect(jsonPath("$.series[0].likes[0]").value(1350))
                .andExpect(jsonPath("$.series[0].postsMeasured[0]").value(3));

        verify(chartDataService).chartData(new ChartDataFilter(day, day, SocialPlatform.TWITTER));
    }

    @Test
    void rejectsReversedOrTooLongRangesAndBadValues() throws Exception {
        mockMvc.perform(get("/api/v1/chart-data").param("from", "2026-11-21").param("to", "2026-11-20"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("from không được sau to"));
        mockMvc.perform(get("/api/v1/chart-data").param("from", "2024-01-01").param("to", "2025-01-01"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Khoảng ngày tối đa 366 ngày"));
        mockMvc.perform(get("/api/v1/chart-data").param("to", "20/11/2026"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(get("/api/v1/chart-data").param("platform", "INSTAGRAM"))
                .andExpect(status().isUnprocessableContent());
        verifyNoInteractions(chartDataService);
    }
}
