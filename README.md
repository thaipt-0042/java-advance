# java-advance — Social Analytics Dashboard

Mini project của khóa **Java Spring Boot – Advance**: ứng dụng web giúp admin tổng hợp và theo dõi lượng tương tác (likes, shares, followers) từ Facebook & Twitter.

## Tech stack

| Category | Choice |
|---|---|
| Framework | Spring Boot 4.1 (Java 21) |
| Build | Maven (wrapper `./mvnw`) |
| Database | MySQL 8 (JPA / Hibernate), H2 cho test |
| API Docs | springdoc-openapi (Swagger UI) |
| Logging | SLF4J + Logback (`logback-spring.xml`) |
| Testing | JUnit 5 + Mockito + MockMvc |

## Cấu trúc

```
src/main/java/com/sunasterisk/socialanalytics
├── SocialAnalyticsApplication.java
├── config/        # cấu hình (OpenAPI, ...)
├── controller/    # REST controllers
├── service/       # business logic
├── repository/    # Spring Data JPA repositories
├── dto/           # request/response DTO
├── entity/        # JPA entities
└── util/          # helper
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

### Profile

| Profile | Khác biệt |
|---|---|
| (mặc định) | `ddl-auto=validate`, tắt Swagger/OpenAPI, log `INFO` |
| `dev` | `ddl-auto=update`, bật Swagger/OpenAPI, log package app ở `DEBUG` |
| `test` | H2 in-memory (MySQL mode), chỉ log ra console |

Các biến cấu hình (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `PORT`, `SPRING_PROFILES_ACTIVE`) xem trong `.env.example`.

## Test

```bash
./mvnw test
```

Test dùng profile `test` với H2 in-memory nên không cần MySQL.
