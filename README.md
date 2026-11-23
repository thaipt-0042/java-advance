# java-advance — Social Analytics Dashboard

Mini project của khóa **Java Spring Boot – Advance**: ứng dụng web giúp admin tổng hợp và theo dõi lượng tương tác (likes, shares, followers) từ Facebook & Twitter.

## Tech stack

| Category | Choice |
|---|---|
| Framework | Spring Boot 4.1 (Java 21) |
| Build | Maven (wrapper `./mvnw`) |
| Database | MySQL 8.4 (JPA / Hibernate), migration bằng Flyway |
| API Docs | springdoc-openapi (Swagger UI) |
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
├── exception/     # exception của app + GlobalExceptionHandler (format lỗi chung)
├── seed/          # dữ liệu mẫu cho profile dev
└── util/          # helper

src/main/resources/db/migration   # Flyway migration (V1__init_schema.sql, ...)
docs/01-thiet-ke-csdl.md          # thiết kế CSDL
docs/02-thiet-ke-api.md           # API CRUD /posts, /metrics và format lỗi
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
- API: `/api/v1/posts`, `/api/v1/metrics` (xem `docs/02-thiet-ke-api.md`)

### Profile

| Profile | Khác biệt |
|---|---|
| (mặc định) | Flyway tạo/cập nhật schema, Hibernate `validate`; tắt Swagger/OpenAPI; log `INFO` |
| `dev` | như mặc định, thêm: bật Swagger/OpenAPI, seed dữ liệu mẫu khi DB trống, log package app ở `DEBUG` |
| `test` | MySQL 8.4 qua Testcontainers, chỉ log ra console |

Các biến cấu hình (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `PORT`, `APP_TIME_ZONE`, `SPRING_PROFILES_ACTIVE`) xem trong `.env.example`.

Schema thay đổi bằng cách thêm file migration mới (`V2__...sql`), không sửa file đã chạy.

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

Test dùng DB chạy trên MySQL 8.4 thật qua Testcontainers nên cần Docker đang chạy (không cần `docker compose up`). Một container dùng chung cho cả lượt test. JaCoCo chỉ báo cáo, chưa đặt ngưỡng coverage tối thiểu.

CI (`.github/workflows/ci.yml`) chạy `./mvnw -B verify` trên GitHub Actions cho mọi PR (kể cả PR có base là nhánh feature) và mỗi lần push lên `main`. Test lỗi thì tải `surefire-reports` ở mục Artifacts của lượt chạy.
