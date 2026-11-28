package com.sunasterisk.socialanalytics.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * The rate API when the SOAP service can't answer. A mock-servlet test has no server port, so with no URL
 * configured the client has nothing to call: the same path as a fault or a timeout (those are covered by
 * {@code ExchangeRateClientTests}).
 */
class ExchangeRateApiUnavailableTests extends ApiTestBase {

    @Test
    void answers502InTheCommonErrorFormat() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/usd-vnd").param("date", "2026-03-01"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BAD_GATEWAY"))
                .andExpect(jsonPath("$.detail").value("Không lấy được tỷ giá, hãy thử lại sau"));
    }

    @Test
    void rejectsAMalformedDateBeforeCallingTheService() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/usd-vnd").param("date", "01/03/2026"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("date"));
    }
}
