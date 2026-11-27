package com.sunasterisk.socialanalytics.controller;

import static com.sunasterisk.socialanalytics.support.Xlsx.POST_HEADER;
import static com.sunasterisk.socialanalytics.support.Xlsx.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.entity.User;
import com.sunasterisk.socialanalytics.entity.UserRole;
import com.sunasterisk.socialanalytics.repository.UserRepository;
import com.sunasterisk.socialanalytics.security.SocialUser;
import com.sunasterisk.socialanalytics.support.StatsQueue;
import com.sunasterisk.socialanalytics.support.Xlsx;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Import through the whole stack on MySQL; each request commits. */
class ImportApiTests extends ApiTestBase {

    /**
     * The whole chain after commit: IMPORT_COMPLETED on the broker, the listener recomputes today's statistics and
     * marks the batch.
     */
    @Test
    void theUploaderOfAFacebookOrXLoginIsStored() throws Exception {
        String t = token();
        User saved = userRepository.save(adminUser(t));
        SocialUser admin = new SocialUser(saved);

        String body = mockMvc.perform(multipart("/api/v1/import-posts")
                        .file(new MockMultipartFile("file", t + ".xlsx", XLSX,
                                Xlsx.of(POST_HEADER, row("TWITTER", t + "-1", "page-" + t))))
                        .with(oauth2Login().oauth2User(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long batchId = ((Number) JsonPath.read(body, "$.batchId")).longValue();
        assertThat(jdbcTemplate.queryForObject("select uploaded_by from import_batches where id = ?", Long.class,
                batchId)).isEqualTo(saved.getId());
    }

    private static User adminUser(String token) {
        User user = new User(SocialPlatform.FACEBOOK, "fb-" + token, "Admin " + token);
        user.setRole(UserRole.ADMIN);
        return user;
    }

    @Test
    void anImportRefreshesTheStatisticsThroughTheQueue() throws Exception {
        StatsQueue.awaitDrained(jmsTemplate);
        String t = token();
        String body = importFile(t + ".xlsx", Xlsx.of(POST_HEADER,
                        row("TWITTER", t + "-1", "page-" + t, null, null, null, 7, 1, 2, 300)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(body, "$.batchId")).longValue();

        long start = System.nanoTime();
        while (jdbcTemplate.queryForObject("select stats_refreshed_at from import_batches where id = ?",
                LocalDateTime.class, batchId) == null) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("statistics refreshed")
                    .isLessThan(Duration.ofSeconds(30));
            Thread.sleep(100);
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(jdbcTemplate.queryForObject("select posts_measured from daily_platform_stats "
                + "where stat_date = ? and platform = 'TWITTER'", Integer.class, today)).isPositive();
    }


    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private UserRepository userRepository;

    @Test
    void importsPostsWithSnapshotsThenReimportUpdatesThem() throws Exception {
        String t = token();
        byte[] first = Xlsx.of(POST_HEADER,
                row("FACEBOOK", t + "-1", "page-" + t, "https://example.com/1", "hello", "2026-03-01 10:00",
                        10, 2, 3, 100),
                row("TWITTER", t + "-2", "page-" + t));

        String body = importFile(t + ".xlsx", first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.createdPosts").value(2))
                .andExpect(jsonPath("$.updatedPosts").value(0))
                .andExpect(jsonPath("$.snapshotsCreated").value(1))
                .andExpect(jsonPath("$.capturedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(body, "$.batchId")).longValue();
        assertThat(jdbcTemplate.queryForMap(
                "select file_name, total_rows, created_posts, updated_posts, snapshots_created, captured_at "
                        + "from import_batches where id = ?", batchId))
                .containsEntry("file_name", t + ".xlsx")
                .containsEntry("total_rows", 2)
                .containsEntry("created_posts", 2)
                .containsEntry("updated_posts", 0)
                .containsEntry("snapshots_created", 1);

        String posts = mockMvc.perform(get("/api/v1/posts").param("q", "page-" + t))
                .andExpect(jsonPath("$.content[*].externalId").value(containsInAnyOrder(t + "-1", t + "-2")))
                .andReturn().getResponse().getContentAsString();
        long facebookId = ((Number) JsonPath.<List<Object>>read(posts,
                "$.content[?(@.externalId == '" + t + "-1')].id").getFirst()).longValue();
        mockMvc.perform(get("/api/v1/posts/{id}", facebookId))
                .andExpect(jsonPath("$.platform").value("FACEBOOK"))
                .andExpect(jsonPath("$.url").value("https://example.com/1"))
                .andExpect(jsonPath("$.content").value("hello"))
                // 10:00 in Asia/Ho_Chi_Minh
                .andExpect(jsonPath("$.publishedAt").value("2026-03-01T03:00:00Z"));
        mockMvc.perform(get("/api/v1/metrics").param("postId", String.valueOf(facebookId)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].likes").value(10))
                .andExpect(jsonPath("$.content[0].followers").value(100));

        // Same file with a new account name for the first post and no content column: content is kept.
        byte[] second = Xlsx.of(row("platform", "externalId", "accountName", "likes", "shares", "comments",
                        "followers"),
                row("FACEBOOK", t + "-1", "renamed-" + t, 20, 2, 3, 100),
                row("TWITTER", t + "-2", "page-" + t));
        importFile(t + ".xlsx", second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdPosts").value(0))
                .andExpect(jsonPath("$.updatedPosts").value(1))
                .andExpect(jsonPath("$.snapshotsCreated").value(1));
        mockMvc.perform(get("/api/v1/posts/{id}", facebookId))
                .andExpect(jsonPath("$.accountName").value("renamed-" + t))
                .andExpect(jsonPath("$.content").value("hello"));
        mockMvc.perform(get("/api/v1/metrics").param("postId", String.valueOf(facebookId)))
                .andExpect(jsonPath("$.content[*].likes").value(contains(20, 10)));
    }

    @Test
    void invalidRowRejectsTheWholeFileAndSavesNothing() throws Exception {
        String t = token();
        byte[] file = Xlsx.of(POST_HEADER,
                row("FACEBOOK", t + "-ok", "page-" + t),
                row("FACEBOOK", t + "-bad", "page-" + t, "not a url", null, null, 1, 2),
                row("FACEBOOK", t + "-ok", "page-" + t));

        importFile(t + ".xlsx", file)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.detail").value("File có 3 lỗi, chưa có dòng nào được lưu"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].column").value("url"))
                .andExpect(jsonPath("$.errors[1].row").value(3))
                .andExpect(jsonPath("$.errors[1].column").doesNotExist())
                .andExpect(jsonPath("$.errors[2].row").value(4))
                .andExpect(jsonPath("$.errors[2].column").value("externalId"))
                .andExpect(jsonPath("$.errors[2].message").value("Bài viết này đã có ở dòng 2"));

        mockMvc.perform(get("/api/v1/posts").param("q", "page-" + t))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(jdbcTemplate.queryForObject("select count(*) from import_batches where file_name = ?",
                Integer.class, t + ".xlsx")).isZero();
    }

    @Test
    void moreThan5000RowsIsRejected() throws Exception {
        importFile("big.xlsx", Xlsx.posts("big-" + token(), 5_001))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.detail").value("File có hơn 5000 dòng dữ liệu"))
                .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void fileThatIsNotXlsxOrHasWrongColumnsReturns400() throws Exception {
        importFile("posts.csv", "platform,externalId,accountName\nFACEBOOK,1,page\n".getBytes())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.detail").value("File không phải file Excel .xlsx đọc được"));
        importFile("posts.xlsx", Xlsx.of(row("platform", "externalId"), row("FACEBOOK", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Thiếu cột bắt buộc: accountName"));
    }

    private ResultActions importFile(String name, byte[] content) throws Exception {
        return mockMvc.perform(multipart("/api/v1/import-posts")
                .file(new MockMultipartFile("file", name, XLSX, content)));
    }
}
