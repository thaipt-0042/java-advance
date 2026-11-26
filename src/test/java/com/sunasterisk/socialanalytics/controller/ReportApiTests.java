package com.sunasterisk.socialanalytics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Export through the whole stack on MySQL. The database is shared, so rows are found by this test's token. */
class ReportApiTests extends ApiTestBase {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void exportsEachPostWithItsLatestSnapshot() throws Exception {
        String t = token();
        long measured = createPost("FACEBOOK", t + "-1", "page-" + t, "=1+1");
        createMetric(measured, "2026-03-01T03:00:00Z", 10);
        createMetric(measured, "2026-03-03T03:00:00Z", 30);
        createMetric(measured, "2026-03-02T03:00:00Z", 20);
        createPost("TWITTER", t + "-2", "page-" + t, "no snapshot");

        byte[] file = mockMvc.perform(get("/api/v1/export-report"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"social-report-" + LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                                + ".xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        Map<String, Map<String, String>> rows = rowsByExternalId(file, t);
        assertThat(rows.get(t + "-1"))
                .containsEntry("platform", "FACEBOOK")
                .containsEntry("accountName", "page-" + t)
                .containsEntry("content", "=1+1")
                .containsEntry("likes", "30")
                .containsEntry("followers", "100")
                // 2026-03-03T03:00Z in Asia/Ho_Chi_Minh
                .containsEntry("capturedAt", "2026-03-03 10:00:00");
        assertThat(rows.get(t + "-2"))
                .containsEntry("platform", "TWITTER")
                .containsEntry("likes", "")
                .containsEntry("capturedAt", "");
    }

    @Test
    void filtersByPlatformAndPublishedDayInVietnamTime() throws Exception {
        String t = token();
        // Far-future days so other tests' posts don't match the date filter.
        published(createPost("FACEBOOK", t + "-before", "page-" + t, "x"), "2091-05-31T16:59:59Z"); // 05-31 23:59
        published(createPost("FACEBOOK", t + "-first", "page-" + t, "x"), "2091-05-31T17:00:00Z"); // 06-01 00:00
        published(createPost("FACEBOOK", t + "-last", "page-" + t, "x"), "2091-06-30T16:59:59Z"); // 06-30 23:59
        published(createPost("FACEBOOK", t + "-after", "page-" + t, "x"), "2091-06-30T17:00:00Z"); // 07-01 00:00
        published(createPost("TWITTER", t + "-twitter", "page-" + t, "x"), "2091-06-10T00:00:00Z");
        createPost("FACEBOOK", t + "-unpublished", "page-" + t, "x");

        byte[] file = mockMvc.perform(get("/api/v1/export-report")
                        .param("from", "2091-06-01").param("to", "2091-06-30").param("platform", "FACEBOOK"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        Map<String, Map<String, String>> rows = rowsByExternalId(file, t);
        assertThat(rows).containsOnlyKeys(t + "-first", t + "-last");
        assertThat(rows.get(t + "-first")).containsEntry("publishedAt", "2091-06-01 00:00:00");
    }

    @Test
    void readsEveryPageOfALargeExport() throws Exception {
        String t = token();
        int count = 2_345;
        // Same far-future day for all, so the filter selects exactly these posts.
        LocalDateTime publishedAt = LocalDateTime.of(2092, 1, 1, 0, 0);
        jdbcTemplate.batchUpdate("""
                insert into posts (platform, external_id, account_name, published_at, created_at, updated_at)
                values ('TWITTER', ?, ?, ?, now(6), now(6))
                """, IntStream.range(0, count)
                .mapToObj(i -> new Object[] {t + "-" + i, "page-" + t, publishedAt})
                .toList());

        byte[] file = mockMvc.perform(get("/api/v1/export-report")
                        .param("from", "2092-01-01").param("to", "2092-01-01").param("platform", "TWITTER"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(rowsByExternalId(file, t)).hasSize(count);
    }

    @Test
    void rejectsInvalidRanges() throws Exception {
        mockMvc.perform(get("/api/v1/export-report").param("from", "2026-03-02").param("to", "2026-03-01"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("rangeOrdered"))
                .andExpect(jsonPath("$.errors[0].message").value("from không được sau to"));
        // 2024 is a leap year: 2024-01-01..2024-12-31 is 366 days, one more is too many.
        mockMvc.perform(get("/api/v1/export-report").param("from", "2024-01-01").param("to", "2024-12-31"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/export-report").param("from", "2024-01-01").param("to", "2025-01-01"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Khoảng ngày tối đa 366 ngày"));
        mockMvc.perform(get("/api/v1/export-report").param("from", "01/03/2026"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("Giá trị không hợp lệ"));
        mockMvc.perform(get("/api/v1/export-report").param("platform", "INSTAGRAM"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("platform"));
    }

    @Test
    void emptyResultIsAFileWithOnlyTheHeader() throws Exception {
        byte[] file = mockMvc.perform(get("/api/v1/export-report")
                        .param("from", "1999-01-01").param("to", "1999-01-31"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheet("Posts");
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(sheet.getRow(0)).extracting(Cell::getStringCellValue).containsExactly("platform",
                    "externalId", "accountName", "url", "content", "publishedAt", "likes", "shares", "comments",
                    "followers", "capturedAt");
        }
    }

    private void published(long postId, String publishedAt) throws Exception {
        mockMvc.perform(put("/api/v1/posts/{id}", postId).contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountName": "page", "content": "x", "publishedAt": "%s", "updatedAt": "%s"}
                        """.formatted(publishedAt, updatedAtOf("/api/v1/posts", postId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    /** Rows of sheet "Posts" whose externalId starts with the token, as column name → displayed text. */
    private static Map<String, Map<String, String>> rowsByExternalId(byte[] file, String token) throws IOException {
        DataFormatter formatter = new DataFormatter();
        Map<String, Map<String, String>> rows = new HashMap<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheet("Posts");
            List<String> header = new ArrayList<>();
            sheet.getRow(0).forEach(cell -> header.add(cell.getStringCellValue()));
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                Map<String, String> values = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    values.put(header.get(i), formatter.formatCellValue(row.getCell(i)));
                }
                if (values.get("externalId").startsWith(token)) {
                    assertThat(rows.put(values.get("externalId"), values)).as("row exported twice").isNull();
                }
            }
        }
        return rows;
    }
}
