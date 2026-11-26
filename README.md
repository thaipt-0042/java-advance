# java-advance — Social Analytics Dashboard

Mini project của khóa **Java Spring Boot – Advance**: ứng dụng web giúp admin tổng hợp và theo dõi lượng tương tác (likes, shares, followers) từ Facebook & Twitter.

## Tech stack

| Category | Choice |
|---|---|
| Framework | Spring Boot 4.1 (Java 21) |
| Build | Maven (wrapper `./mvnw`) |
| Database | MySQL 8.4 (JPA / Hibernate), migration bằng Flyway |
| API Docs | springdoc-openapi (Swagger UI) |
| Excel | Apache POI (`poi-ooxml`) |
| Logging | SLF4J + Logback (`logback-spring.xml`) |
| Testing | JUnit 5 + Mockito + MockMvc, Testcontainers (MySQL) |

## Cấu trúc

```
src/main/java/com/sunasterisk/socialanalytics
├── SocialAnalyticsApplication.java
├── config/        # cấu hình (OpenAPI, Clock, JPA auditing)
├── controller/    # REST controllers
├── service/       # business logic
├── repository/    # Spring Data JPA repositories
├── dto/           # request/response DTO
├── entity/        # JPA entities
├── excel/         # đọc file Excel vào record bằng Reflection (@ExcelColumn)
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
# 1. Tạo file cấu hình local (app và Docker Compose cùng đọc file này)
cp .env.example .env

# 2. Khởi động MySQL
docker compose up -d

# 3. Chạy ứng dụng (profile dev đã bật sẵn trong .env.example)
./mvnw spring-boot:run
```

- Health: http://localhost:8080/actuator/health
- Swagger UI (chỉ profile `dev`): http://localhost:8080/swagger-ui.html
- OpenAPI JSON (chỉ profile `dev`): http://localhost:8080/v3/api-docs
- API: `/api/v1/posts`, `/api/v1/metrics` (xem `docs/02-thiet-ke-api.md`), `/api/v1/import-posts` (xem dưới)

### Profile

| Profile | Khác biệt |
|---|---|
| (mặc định) | Flyway tạo/cập nhật schema, Hibernate `validate`; tắt Swagger/OpenAPI; log `INFO` |
| `dev` | như mặc định, thêm: bật Swagger/OpenAPI, seed dữ liệu mẫu khi DB trống, log package app ở `DEBUG` |
| `test` | MySQL 8.4 qua Testcontainers, chỉ log ra console |

Các biến cấu hình (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `PORT`, `FORWARD_HEADERS_STRATEGY`, `APP_TIME_ZONE`, `SPRING_PROFILES_ACTIVE`) xem trong `.env.example`.

Schema thay đổi bằng cách thêm file migration mới (`V2__...sql`), không sửa file đã chạy.

## Import bài viết từ Excel

`POST /api/v1/import-posts`, `multipart/form-data`, trường `file` là file `.xlsx`:

```bash
curl -F "file=@posts.xlsx" http://localhost:8080/api/v1/import-posts
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
- Giới hạn: file tối đa 5MB (lớn hơn thì 413), tối đa 5.000 dòng dữ liệu (422). File không phải `.xlsx` đọc được (kể cả `.xls`, file có mật khẩu), thiếu cột bắt buộc, không có dòng dữ liệu thì 400.
- Mỗi lần import thành công ghi một dòng `import_batches`. Response: `{batchId, totalRows, createdPosts, updatedPosts, snapshotsCreated, capturedAt}`.

## Test

```bash
./mvnw test      # chạy test
./mvnw verify    # chạy test + báo cáo coverage JaCoCo ở target/site/jacoco/index.html
```

| Loại | Ở đâu | Cách test |
|---|---|---|
| Unit test service | `service/*ServiceTests` | JUnit 5 + Mockito, repository được mock; không cần Spring hay DB |
| Test controller | `controller/*ControllerTests` | `@WebMvcTest` + MockMvc, service là `@MockitoBean`: binding tham số, validation, status, format lỗi |
| Test repository | `repository/*RepositoryTests` | `@DataJpaTest` trên MySQL thật, mỗi test rollback: query tự viết, ràng buộc của schema |
| Test API đầy đủ | `controller/*ApiTests` | `@SpringBootTest` + MockMvc trên MySQL thật, không rollback (mỗi request commit như production); gồm cả test hai request chen nhau |
| Đọc Excel | `excel/ExcelReaderTests` | Unit test, file `.xlsx` tạo trong bộ nhớ bằng `support/Xlsx` (không commit file nhị phân) |
| Server thật | `controller/ForwardedHeadersApiTests`, `ImportUploadLimitApiTests` | `RANDOM_PORT` + `java.net.http`: những gì Tomcat làm mà MockMvc bỏ qua (header proxy, giới hạn upload) |

Test dùng DB chạy trên MySQL 8.4 thật qua Testcontainers nên cần Docker đang chạy (không cần `docker compose up`). Một container dùng chung cho cả lượt test. JaCoCo chỉ báo cáo, chưa đặt ngưỡng coverage tối thiểu.

CI (`.github/workflows/ci.yml`) chạy `./mvnw -B verify` trên GitHub Actions cho mọi PR (kể cả PR có base là nhánh feature) và mỗi lần push lên `main`. Test lỗi thì tải `surefire-reports` ở mục Artifacts của lượt chạy.
