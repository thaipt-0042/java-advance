# java-advance — Social Analytics Dashboard

Mini project của khóa **Java Spring Boot – Advance**: ứng dụng web giúp admin tổng hợp và theo dõi lượng tương tác (likes, shares, followers) từ Facebook & Twitter.

## Tech stack

| Category | Choice |
|---|---|
| Framework | Spring Boot 4.1 (Java 21) |
| Build | Maven (wrapper `./mvnw`) |
| Database | MySQL 8.4 (JPA / Hibernate), migration bằng Flyway |
| API Docs | springdoc-openapi (Swagger UI) |
| Security | Spring Security 7: đăng nhập Facebook / X (OAuth2, PKCE), form login demo ở `dev`, CSRF; phiên lưu MySQL (Spring Session JDBC) |
| Giao diện | Thymeleaf (`templates/`), JS thuần (`static/js/dashboard.js`), Chart.js 4.6.1, SockJS 1.6.1, STOMP.js 7.3.0 (WebJars, phiên bản ghim trong `pom.xml`) |
| Excel | Apache POI (`poi-ooxml`) |
| Background job | `@Scheduled` + `@Async` (`ThreadPoolTaskExecutor`), ShedLock trên MySQL |
| Messaging | JMS, Apache Artemis 2.53 (trước đây là ActiveMQ Artemis), chạy bằng Docker Compose |
| Logging | SLF4J + Logback (`logback-spring.xml`) |
| Testing | JUnit 5 + Mockito + MockMvc, Testcontainers (MySQL) |

## Cấu trúc

```
src/main/java/com/sunasterisk/socialanalytics
├── SocialAnalyticsApplication.java
├── config/        # cấu hình (OpenAPI, Clock, JPA auditing)
├── controller/    # REST controllers
├── web/           # trang Thymeleaf (dashboard)
├── security/      # Spring Security: filter chain, CSRF, đăng nhập demo
├── service/       # business logic
│   └── metrics/   # job cập nhật số liệu (chạy song song từng tài khoản)
├── job/           # lịch chạy (@Scheduled), chỉ gọi vào service
├── client/        # API Facebook / X (bản giả lập)
├── messaging/     # JMS: gửi message sau commit, listener tính lại thống kê, log DLQ
├── repository/    # Spring Data JPA repositories
├── dto/           # request/response DTO
├── entity/        # JPA entities
├── excel/         # đọc / ghi file Excel từ record bằng Reflection (@ExcelColumn)
├── exception/     # exception của app + GlobalExceptionHandler (format lỗi chung)
├── seed/          # dữ liệu mẫu cho profile dev
└── util/          # helper

src/main/resources/db/migration   # Flyway migration (V1__init_schema.sql, ...)
docs/00-phan-tich-yeu-cau.md      # phân tích yêu cầu, đối chiếu đề bài ↔ issue
docs/01-thiet-ke-csdl.md          # thiết kế CSDL: bảng hiện có + lộ trình schema
docs/02-thiet-ke-api.md           # API đã làm, API dự kiến, format lỗi
docs/03-kien-truc.md              # package, bảo mật, job, JMS, WebSocket, SOAP
docs/cau-hoi-lam-ro.md            # điểm đề chưa rõ (chờ trả lời), điều cần báo mentor
.github/workflows/ci.yml          # CI: ./mvnw verify trên GitHub Actions
```

## Chạy local

Yêu cầu: JDK 21+, Docker.

```bash
# 1. Tạo file cấu hình local (app và Docker Compose cùng đọc file này),
#    rồi đặt APP_DEMO_ADMIN_PASSWORD, APP_DEMO_USER_PASSWORD (bắt buộc với profile dev)
cp .env.example .env

# 2. Khởi động MySQL và broker JMS (Apache Artemis)
docker compose up -d

# 3. Chạy ứng dụng (profile dev đã bật sẵn trong .env.example)
./mvnw spring-boot:run
```

- Đăng nhập (chỉ profile `dev`): http://localhost:8080/login, tài khoản `demo-admin` hoặc `demo-user` (xem "Đăng nhập và quyền")
- Dashboard: http://localhost:8080/dashboard (thời điểm cập nhật số liệu cuối, lần chạy job gần nhất; `ADMIN` có nút "Chạy job ngay")
- Health: http://localhost:8080/actuator/health
- Swagger UI (chỉ profile `dev`): http://localhost:8080/swagger-ui.html
- OpenAPI JSON (chỉ profile `dev`): http://localhost:8080/v3/api-docs
- API: `/api/v1/posts`, `/api/v1/metrics` (xem `docs/02-thiet-ke-api.md`), `/api/v1/import-posts`, `/api/v1/export-report`, `/api/v1/jobs/update-metrics`, `/api/v1/chart-data` (xem dưới)

### Profile

| Profile | Khác biệt |
|---|---|
| (mặc định) | Flyway tạo/cập nhật schema, Hibernate `validate`; tắt Swagger/OpenAPI; log `INFO` |
| `dev` | như mặc định, thêm: bật Swagger/OpenAPI, form đăng nhập demo, seed dữ liệu mẫu khi DB trống, log package app ở `DEBUG` |
| `test` | MySQL 8.4 và Artemis qua Testcontainers, form đăng nhập demo với mật khẩu cố định, chỉ log ra console |

Các biến cấu hình (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `PORT`, `FORWARD_HEADERS_STRATEGY`, `APP_TIME_ZONE`, `ARTEMIS_HOST`, `ARTEMIS_PORT`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD`, `METRICS_JOB_CRON`, `METRICS_JOB_THREADS`, `FACEBOOK_CLIENT_ID`, `FACEBOOK_CLIENT_SECRET`, `TWITTER_CLIENT_ID`, `TWITTER_CLIENT_SECRET`, `APP_TOKEN_ENCRYPTION_KEY`, `APP_ADMIN_ACCOUNTS`, `APP_DEMO_ADMIN_PASSWORD`, `APP_DEMO_USER_PASSWORD`, `SPRING_PROFILES_ACTIVE`) xem trong `.env.example`.

### Đăng nhập và quyền

- **Đăng nhập:** trang `/login` có nút "Đăng nhập bằng Facebook" / "Đăng nhập bằng X" cho mỗi provider đã cấu hình, và ở profile `dev` thêm form của hai tài khoản demo `demo-admin` (`ADMIN`), `demo-user` (`USER`) (mật khẩu trong `.env`, tối thiểu 8 ký tự).
  - Bật provider: đặt cả `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` (hoặc `TWITTER_*`) của app tạo trên trang developer, khai báo callback `<địa chỉ app>/login/oauth2/code/facebook` (hoặc `/twitter`). Bật provider nào thì bắt buộc có `APP_TOKEN_ENCRYPTION_KEY` (`openssl rand -base64 32`), thiếu thì app không khởi động.
  - Đăng nhập thành công thì về dashboard; thất bại (từ chối cấp quyền, `state` sai) thì về `/login` kèm thông báo.
  - Mỗi lần đăng nhập, bảng `users` được tạo/cập nhật theo `provider` + id của tài khoản, role đặt lại theo `APP_ADMIN_ACCOUNTS` (`FACEBOOK:<id>,TWITTER:<id>`; còn lại là `USER`).
  - Token truy cập được lưu mã hóa AES-GCM (`oauth2_authorized_client`), không log, không trả ra API. Đổi khóa thì token cũ không đọc được nữa: người dùng chỉ cần đăng nhập lại.
  - Phiên đăng nhập lưu trong MySQL (Spring Session JDBC, cookie `SESSION`): restart app không đăng xuất mọi người, nhiều instance dùng chung phiên.
  - Import và chạy job tay ghi lại người làm (`import_batches.uploaded_by`, `metric_update_runs.triggered_by`); tài khoản demo không có trong `users` nên để trống.
- **Quyền** (`docs/cau-hoi-lam-ro.md` Q1): đã đăng nhập thì đọc được mọi API `GET` (kể cả export); tạo/sửa/xóa, import và các lệnh khác chỉ `ADMIN`. Đường dẫn không khai báo thì bị từ chối.
- **Lỗi:** API chưa đăng nhập trả 401 `UNAUTHORIZED`, không đủ quyền hoặc thiếu CSRF token trả 403 `FORBIDDEN`, cùng format JSON với các lỗi khác. Trang web chưa đăng nhập thì chuyển về `/login`.
- **CSRF:** mọi request thay đổi dữ liệu cần token. Token nằm trong cookie `XSRF-TOKEN` (JavaScript đọc được); gọi API thì gửi lại giá trị đó trong header `X-XSRF-TOKEN`. API chỉ nhận token qua header, không nhận trường `_csrf` (kể cả upload multipart). Form của trang web (đăng nhập, đăng xuất) gửi trường ẩn `_csrf`. Swagger UI tự gửi header này.

Gọi API bằng `curl` (profile `dev`):

```bash
# Lấy cookie phiên và token của trang đăng nhập, rồi đăng nhập
curl -s -c cookies.txt http://localhost:8080/login \
  | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//; s/"$//' > csrf.txt
curl -s -b cookies.txt -c cookies.txt -d "username=demo-admin&password=$APP_DEMO_ADMIN_PASSWORD&_csrf=$(cat csrf.txt)" \
  http://localhost:8080/login
# Đọc dữ liệu: chỉ cần cookie (lần gọi này cũng cấp cookie XSRF-TOKEN mới)
curl -s -b cookies.txt -c cookies.txt "http://localhost:8080/api/v1/posts?size=5"
# Ghi dữ liệu: gửi kèm token trong header
curl -s -b cookies.txt -H "X-XSRF-TOKEN: $(awk '$6 == "XSRF-TOKEN" {print $7}' cookies.txt)" \
  -F "file=@posts.xlsx" http://localhost:8080/api/v1/import-posts
```

Schema thay đổi bằng cách thêm file migration mới (`V2__...sql`), không sửa file đã chạy.

## Import bài viết từ Excel

`POST /api/v1/import-posts`, `multipart/form-data`, trường `file` là file `.xlsx`:

```bash
# Cần đăng nhập ADMIN và header X-XSRF-TOKEN, xem "Đăng nhập và quyền"
curl -b cookies.txt -H "X-XSRF-TOKEN: ..." -F "file=@posts.xlsx" http://localhost:8080/api/v1/import-posts
```

**File:** đọc sheet đầu tiên; dòng 1 là tiêu đề. Cột nhận theo tên (không phân biệt hoa thường, thứ tự tùy ý); cột lạ hoặc trùng tên thì từ chối cả file. Dòng trống bị bỏ qua.

| Cột | Bắt buộc | Giá trị |
|---|---|---|
| `platform` | có | `FACEBOOK` hoặc `TWITTER` (viết đúng hoa thường, như API) |
| `externalId` | có | id bài trên nền tảng, tối đa 100 ký tự. Id dài từ 16 chữ số trở lên phải nhập ô dạng text, vì Excel đã làm tròn số đó |
| `accountName` | có | tối đa 100 ký tự |
| `url` | không | `http(s)://…`, tối đa 1000 ký tự |
| `content` | không | tối đa 5000 ký tự |
| `publishedAt` | không | ô kiểu ngày giờ (hiểu theo giờ `APP_TIME_ZONE`), hoặc text `2026-03-01 10:00` (cũng theo giờ đó) / `2026-03-01T03:00:00Z` (có múi giờ) |
| `likes`, `shares`, `comments`, `followers` | không | số nguyên ≥ 0; có một cột thì phải có đủ cả bốn trong dòng đó |

**Quy tắc** (`docs/cau-hoi-lam-ro.md` Q3, Q4):

- **Tất cả hoặc không có gì:** có dòng lỗi thì không lưu gì, trả 422 kèm mọi lỗi theo số dòng Excel (`errors: [{row, column, message}]`). Sửa file rồi import lại cả file.
- **Bài đã có** (cùng `platform` + `externalId`, so như cột trong DB: không phân biệt hoa thường và dấu) thì **cập nhật** theo file. Chỉ cập nhật cột có trong file: file không có cột `content` thì giữ nguyên nội dung cũ; ô trống trong cột có mặt thì xóa giá trị cũ (giống PUT). Bài không có gì thay đổi thì không bị ghi lại và không tính vào `updatedPosts`.
- Một bài xuất hiện hai lần trong cùng file là lỗi dòng.
- Dòng có số liệu tạo thêm một snapshot. Mọi snapshot của một lần import có chung `capturedAt` là thời điểm import.
- Giới hạn: file tối đa 5MB (lớn hơn thì 413), tối đa 5.000 dòng dữ liệu (422; đọc tới dòng thứ 5.001 thì dừng, không đọc hết file). File không phải `.xlsx` đọc được (kể cả `.xls`, file có mật khẩu), thiếu cột bắt buộc, không có dòng dữ liệu thì 400.
- Mỗi lần import thành công ghi một dòng `import_batches`. Response: `{batchId, totalRows, createdPosts, updatedPosts, snapshotsCreated, capturedAt}`.

## Xuất báo cáo Excel

`GET /api/v1/export-report`, trả file `social-report-<ngày xuất>.xlsx`:

```bash
# Cần đăng nhập (USER hoặc ADMIN)
curl -b cookies.txt -OJ "http://localhost:8080/api/v1/export-report?from=2026-11-01&to=2026-11-30&platform=FACEBOOK"
```

- Tham số đều tùy chọn: `from` / `to` là ngày đăng bài (`publishedAt`) theo giờ `APP_TIME_ZONE`, bao gồm cả hai đầu, tối đa 366 ngày; `platform` là `FACEBOOK` hoặc `TWITTER`. Có `from` hoặc `to` thì bài không có ngày đăng bị bỏ.
- Sheet `Posts`: mỗi bài một dòng, theo thứ tự tạo, kèm **snapshot mới nhất** (`likes`, `shares`, `comments`, `followers`, `capturedAt`; trống nếu bài chưa có snapshot). Thời gian là ô ngày giờ theo giờ `APP_TIME_ZONE`. Tên cột giống file import, thêm `capturedAt`.
- Sheet `Daily`: thống kê từng ngày trong `from`..`to` (mọi ngày nếu không lọc) và nền tảng, xem "Thống kê tổng hợp và JMS" (`docs/cau-hoi-lam-ro.md` Q11).
- Tối đa 100.000 bài mỗi file (422, hãy thu hẹp bộ lọc). File được tạo bằng `SXSSFWorkbook`: chỉ giữ 100 dòng mỗi sheet trong bộ nhớ, phần còn lại ghi ra file tạm (nén) và được xóa sau khi gửi.
- Nội dung bài được ghi dạng text, kể cả khi bắt đầu bằng `=`: Excel không chạy nó như công thức.

## Job cập nhật số liệu

Job `updateSocialMetricsJob` chạy mỗi giờ (`METRICS_JOB_CRON`, mặc định `0 0 * * * *` theo `APP_TIME_ZONE`). Mỗi lần chạy:

- Lấy mọi tài khoản đang có bài (`platform` + `accountName`, so như cột trong DB). Mỗi tài khoản là một tác vụ chạy song song (`METRICS_JOB_THREADS` luồng, mặc định 4, phải nhỏ hơn số kết nối DB).
- Gọi API giả lập của Facebook / X (`docs/cau-hoi-lam-ro.md` Q6). Số liệu tăng dần theo giờ, có độ trễ và tỷ lệ lỗi cấu hình được (`app.metrics-job.mock.*`; profile `dev` cho 10% lời gọi lỗi). Lời gọi API nằm ngoài transaction.
- Ghi một snapshot cho mỗi bài trong một transaction ngắn. Mọi snapshot của một lần chạy có chung `capturedAt` là lúc bắt đầu chạy; `followers` là của tài khoản.
- Một tài khoản lỗi không dừng các tài khoản khác. Kết quả ghi vào `metric_update_runs`: `SUCCEEDED`, `PARTIAL` (một số tài khoản lỗi, có tên trong `errorMessage`) hoặc `FAILED`. Quá `app.metrics-job.account-timeout` (mặc định 5 phút) thì tài khoản chưa xong tính là lỗi.

**Không chạy chồng**, kể cả khi chạy nhiều instance: khóa ShedLock `metrics-job` trên bảng `shedlock` của MySQL, thời gian theo đồng hồ của DB. Lần chạy theo lịch không lấy được khóa thì bỏ qua lượt đó. Instance chết giữa chừng thì khóa tự hết hạn sau `app.metrics-job.lock-at-most-for` (mặc định 10 phút); lần chạy sau đánh dấu lần chạy bỏ dở là `FAILED`.

**API:**

- `POST /api/v1/jobs/update-metrics` (`ADMIN`, CSRF): chạy ngay, chạy nền, trả 202 `{runId}`. Job đang chạy (ở bất kỳ instance nào) thì 409.
- `GET /api/v1/jobs/update-metrics/latest`: lần chạy gần nhất (`status`, `triggerType`, `startedAt`, `finishedAt`, số tài khoản, số tài khoản lỗi, số snapshot, `errorMessage`) và `lastUpdatedAt` (lần `SUCCEEDED` / `PARTIAL` gần nhất). Chưa chạy lần nào thì các trường là `null`.

**Dashboard** (`/dashboard`, cần đăng nhập): "Cập nhật lần cuối" (lần `SUCCEEDED` / `PARTIAL` gần nhất) và trạng thái lần chạy gần nhất, theo giờ `APP_TIME_ZONE`. `ADMIN` có nút "Chạy job ngay": form gửi thường kèm trường ẩn `_csrf`, chạy xong request thì quay lại dashboard với thông báo "đã bắt đầu" hoặc "job đang chạy".

Cấu hình sai (cron, kích thước pool, `lock-at-most-for` không dài hơn `account-timeout` ít nhất 1 phút, tỷ lệ lỗi ngoài 0..1) thì app không khởi động.

## Thống kê tổng hợp và JMS

Bảng `daily_platform_stats`: mỗi ngày (theo `APP_TIME_ZONE`) × nền tảng một dòng. Mỗi ngày là trạng thái **tính đến hết ngày đó**: mỗi bài lấy snapshot mới nhất trước 00:00 hôm sau, nên ngày thiếu snapshot không tụt về 0 (`docs/cau-hoi-lam-ro.md` Q12). `followers` cộng theo tài khoản, không theo bài. `postsMeasured` < `postsTracked` nghĩa là ngày đó đo thiếu.

Bảng được tính lại qua queue `analytics.stats-refresh` trên Apache Artemis (Q5, Q10):

| Message | Gửi khi | Tính lại từ ngày |
|---|---|---|
| `IMPORT_COMPLETED` | import Excel xong | hôm nay, hoặc ngày snapshot đầu tiên của bài vừa đổi `accountName` |
| `METRICS_UPDATED` | job chạy xong và có ghi snapshot | ngày bắt đầu chạy |
| `METRICS_CHANGED` | thêm / sửa / xóa snapshot, đổi `accountName` hoặc xóa bài qua API, seed dữ liệu `dev` | ngày sớm nhất bị ảnh hưởng |

- Message chỉ gửi **sau khi transaction commit** (`@TransactionalEventListener(AFTER_COMMIT)`); rollback thì không gửi. Broker không kết nối được thì lỗi được log, request vẫn thành công; thống kê cập nhật ở message kế tiếp (job mỗi giờ tính lại hôm nay).
- Listener chạy một luồng, tính lại từng ngày từ `fromDate` tới hôm nay trong một transaction (xóa dòng của ngày rồi `INSERT … SELECT` từ dữ liệu gốc), nên nhận trùng message vẫn ra cùng kết quả. Transaction ở READ COMMITTED để `INSERT … SELECT` không khóa các dòng `posts` / `social_metrics` đang đọc.
- Listener lỗi (kể cả message không đọc được) thì broker giao lại sau 1, 2, 4 giây; lỗi lần thứ 4 thì chuyển sang `analytics.stats-refresh.dlq` và được log `ERROR` kèm nội dung. Cấu hình ở `docker/artemis/broker.properties` (Docker Compose và test dùng chung).
- Import xong thì `import_batches.stats_refreshed_at` được điền khi thống kê đã tính lại.
- File export có thêm sheet `Daily` (thống kê các ngày trong `from`..`to`).

## Biểu đồ và WebSocket

- `GET /api/v1/chart-data?from=&to=&platform=`: thống kê theo ngày cho biểu đồ, mặc định 7 ngày gần nhất, tối đa 366 ngày. Mỗi mảng (`postsTracked`, `postsMeasured`, `likes`, `shares`, `comments`, `followers`) dài bằng `dates`. Ngày không có dữ liệu giữ số của ngày gần nhất trước đó với `postsMeasured` = 0; trước lần đo đầu tiên thì là 0 (`docs/cau-hoi-lam-ro.md` Q12). Kèm `lastUpdatedAt`.
- WebSocket (STOMP qua SockJS) ở `/ws`, topic `/topic/metrics`. Sau khi thống kê được tính lại thì gửi `STATS_UPDATED {fromDate, toDate}`, sau mỗi lần chạy job thì gửi `JOB_FINISHED {runId, status, finishedAt}`. Event không chứa số liệu: trang gọi lại `/api/v1/chart-data`.
- Chỉ người đã đăng nhập mới kết nối được. Frame `CONNECT` phải kèm CSRF token (dạng đã mask, lấy từ thẻ meta `_csrf` của dashboard) trong header `X-XSRF-TOKEN`. Chỉ được subscribe `/topic/metrics`. Tầng HTTP không kiểm CSRF riêng cho `/ws/**`, vì SockJS không gắn được header vào các request XHR / iframe khi WebSocket bị chặn.
- Dashboard vẽ biểu đồ theo ngày bằng Chart.js (chọn likes / shares / comments / followers, 7 / 30 / 90 ngày; điểm của ngày không đo được vẽ dạng ✕). Trang kết nối `/ws`, nhận `STATS_UPDATED` thì tải lại `/chart-data`, nhận `JOB_FINISHED` thì tải lại khối "Lần chạy gần nhất" (`GET /dashboard/run-status`), không reload trang. Mất kết nối thì tự kết nối lại sau 5 giây và tải lại biểu đồ. Thư viện JS phục vụ từ chính app (`/webjars/...`), không dùng CDN, kể cả trang iframe của SockJS.
- Giới hạn: broker STOMP nằm trong bộ nhớ của từng instance. Event chỉ tới trình duyệt nối vào instance đã phát ra nó: `STATS_UPDATED` do instance đang giữ queue thống kê phát, `JOB_FINISHED` do instance đã chạy job phát.

## Test

```bash
./mvnw test      # chạy test
./mvnw verify    # chạy test + báo cáo coverage JaCoCo ở target/site/jacoco/index.html
```

| Loại | Ở đâu | Cách test |
|---|---|---|
| Unit test service | `service/*ServiceTests` | JUnit 5 + Mockito, repository được mock; không cần Spring hay DB |
| Test controller | `controller/*ControllerTests` | `@WebMvcTest` + MockMvc, service là `@MockitoBean`: binding tham số, validation, status, format lỗi. Request mặc định là `ADMIN` kèm CSRF header (`support/AdminRequests`) |
| Test repository | `repository/*RepositoryTests` | `@DataJpaTest` trên MySQL thật, mỗi test rollback: query tự viết, ràng buộc của schema |
| Test API đầy đủ | `controller/*ApiTests` | `@SpringBootTest` + MockMvc trên MySQL thật, không rollback (mỗi request commit như production); gồm cả test hai request chen nhau. Cũng chạy với `support/AdminRequests` |
| Đọc / ghi Excel | `excel/ExcelReaderTests`, `ExcelWriterTests` | Unit test, file `.xlsx` tạo và đọc lại trong bộ nhớ (`support/Xlsx`, không commit file nhị phân) |
| Job | `service/metrics/*Tests`, `client/MockSocialMetricsClientTests`, `controller/JobApiTests` | Service với Mockito (tài khoản lỗi, quá thời gian, hàng đợi đầy, khóa đang bị giữ); updater và API trên MySQL thật (bài bị xóa giữa chừng, lần chạy bỏ dở, lịch chạy bỏ lượt khi bị khóa) |
| Trang web | `web/DashboardControllerTests`, `security/LoginFlowApiTests` | `@WebMvcTest` với template và security thật (giờ Việt Nam, nút chỉ cho `ADMIN`, trang lỗi); server thật: đăng nhập rồi bấm nút với `_csrf` của form |
| JMS và thống kê | `messaging/*Tests`, `repository/DailyStatsRepositoryTests`, `service/Stats*Tests` | Broker thật (Testcontainers): chỉ gửi sau commit, rollback thì không gửi, message lỗi 4 lần vào DLQ; truy vấn thống kê với ví dụ Q12 trên MySQL |
| WebSocket | `websocket/WebSocketApiTests` | Server thật, client STOMP qua SockJS với cookie đăng nhập và token từ thẻ meta, cả transport WebSocket lẫn chỉ XHR: nhận event; thiếu token, chưa đăng nhập, subscribe topic khác thì bị từ chối |
| Quyền truy cập | `security/SecurityConfigTests`, `DemoLoginDisabledTests`, `OAuth2LoginTests`, `EncryptedTokensTests` | `@WebMvcTest` với cấu hình security thật: 401/403, CSRF, đường dẫn không khai báo, đăng nhập demo |
| Server thật | `controller/ForwardedHeadersApiTests`, `ImportUploadLimitApiTests`, `security/LoginFlowApiTests` | `RANDOM_PORT` + `java.net.http` (`support/LoginSession` đăng nhập như trình duyệt): những gì Tomcat làm mà MockMvc bỏ qua (header proxy, giới hạn upload, cookie CSRF) |

Test dùng DB chạy trên MySQL 8.4 thật qua Testcontainers nên cần Docker đang chạy (không cần `docker compose up`). Một container dùng chung cho cả lượt test. JaCoCo chỉ báo cáo, chưa đặt ngưỡng coverage tối thiểu.

CI (`.github/workflows/ci.yml`) chạy `./mvnw -B verify` trên GitHub Actions cho mọi PR (kể cả PR có base là nhánh feature) và mỗi lần push lên `main`. Test lỗi thì tải `surefire-reports` ở mục Artifacts của lượt chạy.
