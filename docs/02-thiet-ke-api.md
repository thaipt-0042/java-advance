# Thiết kế API

- **Đã làm** (issue #4): API CRUD `/api/v1/posts`, `/api/v1/metrics` và định dạng lỗi chung, hai mục đầu của file này.
- **Dự kiến**: các API và trang của những giai đoạn sau, mục [API dự kiến](#api-dự-kiến). Chưa có code; quyền truy cập theo `cau-hoi-lam-ro.md` (Q1, đã chốt).

Swagger UI (profile `dev`): http://localhost:8080/swagger-ui.html

## Endpoint CRUD (đã làm, #4)

| Method | Path | Mô tả | Thành công | Lỗi chính |
|---|---|---|---|---|
| GET | `/api/v1/posts` | Danh sách bài viết, lọc `platform`, `q`; phân trang `page`, `size` | 200 | 422 |
| GET | `/api/v1/posts/{id}` | Chi tiết | 200 | 404 |
| POST | `/api/v1/posts` | Thêm bài viết, header `Location` trỏ tới bài mới | 201 | 409, 422 |
| PUT | `/api/v1/posts/{id}` | Thay các trường sửa được (`accountName`, `url`, `content`, `publishedAt`), kèm `updatedAt` đã đọc | 200 | 404, 409, 422 |
| DELETE | `/api/v1/posts/{id}` | Xóa bài viết và mọi snapshot của nó | 204 | 404 |
| GET | `/api/v1/metrics` | Danh sách snapshot, lọc `postId`, `from`, `to`; phân trang | 200 | 404, 422 |
| GET | `/api/v1/metrics/{id}` | Chi tiết | 200 | 404 |
| POST | `/api/v1/metrics` | Thêm snapshot cho bài viết | 201 | 404, 409, 422 |
| PUT | `/api/v1/metrics/{id}` | Thay `capturedAt` và các số đếm, kèm `updatedAt` đã đọc | 200 | 404, 409, 422 |
| DELETE | `/api/v1/metrics/{id}` | Xóa snapshot | 204 | 404 |

- Request/response là DTO (`dto/`), không trả entity ra ngoài. Thời gian theo ISO-8601 UTC, ví dụ `2026-03-01T03:00:00Z`.
- `platform` + `externalId` là định danh của bài viết, `postId` là chủ của snapshot: không sửa được qua PUT. Muốn đổi thì xóa rồi tạo lại.
- PUT thay toàn bộ các trường sửa được: trường không gửi (`url`, `content`, `publishedAt`) thành `null`.
- PUT bắt buộc gửi `updatedAt` mà client đọc được từ GET (hoặc từ response của lần sửa trước). Bản ghi đã bị sửa sau đó (`updatedAt` khác) thì trả 409, client tải lại rồi sửa tiếp. Xem mục "Ghi đồng thời".

### Danh sách và phân trang

- Trả `{content, page, size, totalElements, totalPages}` (`PageResponse`), không trả JSON của `Page` vì Spring Data không đảm bảo format đó.
- `page` từ 0 (mặc định 0, tối đa 10.000.000), `size` từ 1 đến 100 (mặc định 20). Trang vượt quá số trang trả `content` rỗng, không lỗi. Giới hạn `page` giữ offset trong phạm vi `int` mà JPA nhận.
- Thứ tự cố định: bài viết mới tạo trước (`id` giảm dần); snapshot mới đo trước (`capturedAt` giảm dần). Không nhận tham số `sort`, nên không có cách đưa tên cột lạ vào truy vấn.
- `q` tìm trong `content` hoặc `accountName`, tối đa 100 ký tự. `%` và `_` trong `q` được escape (ký tự escape `!`, không dùng `\` vì trong chuỗi MySQL nó cũng là ký tự escape), nên `q=50%` chỉ khớp chuỗi "50%". Collation `utf8mb4_0900_ai_ci` nên không phân biệt hoa thường và dấu (`tri thai` khớp `Trí Thái`).
- `from`/`to` là khoảng `[from, to)`. `from` phải trước `to`, nếu không trả 422 với `field` là `rangeValid` (lỗi trên cả cặp tham số).

### Thời gian

`created_at`, `updated_at`, `captured_at`, `published_at` là `DATETIME(6)`. Thời gian client gửi lên và thời gian auditing đều được cắt về micro giây trước khi lưu (`DbTime`), nên giá trị trả về ngay sau POST/PUT trùng với giá trị đọc lại sau đó, và kiểm tra trùng `capturedAt` so đúng giá trị sẽ lưu.

### Ghi đồng thời

Hai request cùng lúc trên một dòng không được thành 500:

- **Sửa** (`PUT`) khóa dòng ngay lần đọc đầu tiên (`findByIdForUpdate`, `SELECT ... FOR UPDATE`). Nếu dòng đang bị request khác xóa thì request sửa chờ, rồi không thấy dòng nữa nên trả 404. Không khóa thì câu UPDATE cập nhật 0 dòng, Hibernate ném `ObjectOptimisticLockingFailureException` và trả 500.
- **Hai người cùng sửa một dòng** (A và B cùng đọc, A lưu trước): khóa chỉ cho hai PUT chạy nối đuôi, nên nếu không kiểm tra thì PUT của B ghi đè thay đổi của A mà không ai biết. Sau khi khóa dòng, service so `updatedAt` trong request với `updatedAt` hiện tại; khác thì trả 409 và không ghi gì. Không cần thêm cột `version`: `updatedAt` đã được cắt về micro giây (xem mục "Thời gian") nên so khớp chính xác.
- **Xóa** là một câu `DELETE ... WHERE id = ?`, không nạp entity trước; xóa 0 dòng thì trả 404.
- **Thêm snapshot** đọc bài viết với khóa chia sẻ (`findByIdForShare`, `SELECT ... FOR SHARE`). Bài đang bị xóa thì trả 404, không phải 409 do vi phạm khóa ngoại.
- **Trùng khóa nghiệp vụ** khi hai request cùng qua bước kiểm tra: ràng buộc UNIQUE chặn request sau, trả 409.

`ConcurrentDeleteApiTests` tái hiện các trường hợp này bằng 2 luồng: xóa dòng trong một transaction chưa commit, chờ đến khi MySQL báo request đang đợi khóa dòng đó (`performance_schema.data_lock_waits`), rồi mới commit. `ConcurrentWriteApiTests` giả lập trường hợp trùng bằng `@MockitoSpyBean`.

## Định dạng lỗi (đã làm, #4)

Mọi lỗi đều trả [`ProblemDetail`](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`) kèm `code`; lỗi validation có thêm `errors`:

```json
{
  "status": 422,
  "title": "Unprocessable Content",
  "detail": "Dữ liệu không hợp lệ",
  "instance": "/api/v1/metrics",
  "code": "VALIDATION",
  "errors": [{"field": "likes", "message": "must be greater than or equal to 0"}]
}
```

| Trường hợp | Status | `code` |
|---|---|---|
| Vi phạm Bean Validation ở body hoặc query (`@NotBlank`, `@Size`, `@PositiveOrZero`, `page`/`size` ngoài khoảng, `from` ≥ `to`, `platform=INSTAGRAM` trong query) | 422 | `VALIDATION` |
| Id không tồn tại, ở path, query (`postId`) hay body (`postId`) | 404 | `NOT_FOUND` |
| Trùng khóa nghiệp vụ, kiểm tra trước trong service: báo rõ bị trùng gì | 409 | `CONFLICT` |
| PUT với `updatedAt` cũ (bản ghi đã bị sửa sau khi client đọc) | 409 | `CONFLICT` |
| `DataIntegrityViolationException` (hai request chen nhau cùng qua bước kiểm tra, ràng buộc DB chặn request sau): câu chung, `log.warn` kèm exception | 409 | `CONFLICT` |
| `PessimisticLockingFailureException` (thua deadlock hoặc chờ khóa dòng quá lâu; transaction đã rollback cả, gửi lại là được): câu chung, `log.warn` kèm exception | 409 | `CONFLICT` |
| JSON hỏng, sai kiểu, enum lạ trong body (`"platform": "INSTAGRAM"`), id trên path không phải số | 400 | `BAD_REQUEST` |
| Sai method / sai `Content-Type` / path không tồn tại | 405 / 415 / 404 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` / `NOT_FOUND` |
| Lỗi không lường trước: không lộ chi tiết, `log.error` kèm exception | 500 | `INTERNAL_ERROR` |

- `GlobalExceptionHandler` kế thừa `ResponseEntityExceptionHandler` nên các lỗi của framework giữ đúng status. Lỗi validation được xử lý bằng cách override `handleMethodArgumentNotValid` (khai thêm `@ExceptionHandler` cho cùng exception sẽ báo "Ambiguous @ExceptionHandler" lúc khởi động). Với lỗi framework, `code` là tên của HTTP status.
- `message` trong `errors` là message mặc định của Bean Validation (tiếng Anh, Hibernate Validator không có bản tiếng Việt). Client nên dựa vào `field` và `code`.
- Body có danh sách thì dùng `List<@NotNull @Valid ...>` để phần tử `null` bị chặn ở validation. API ở issue này chưa có body dạng danh sách; quy tắc này áp dụng cho import ở #6.
- **Lưu ý cho #8 (Spring Security):** handler `Exception` chung sẽ biến `AccessDeniedException`/`AuthenticationException` ném từ controller (ví dụ `@PreAuthorize`) thành 500. Khi thêm security phải thêm handler riêng trả 403/401 cùng format, kèm test user thiếu quyền.

## API dự kiến

Theo cùng quy ước với phần đã làm: tiền tố `/api/v1`, DTO, lỗi dạng `ProblemDetail` + `code`, thời gian ISO-8601 UTC; tham số dạng ngày (`LocalDate`) hiểu theo giờ Việt Nam. Đề ghi đường dẫn không có tiền tố (`/import-posts`, `/export-report`, `/chart-data`), xem "Cần báo mentor" trong `cau-hoi-lam-ro.md`.

### Quyền sau khi có Spring Security (#8)

| Nhóm | Quyền (đã chốt, Q1) |
|---|---|
| `GET /api/v1/**`, `GET /api/v1/export-report` | User đã đăng nhập (`USER`, `ADMIN`) |
| `POST`/`PUT`/`DELETE` `/api/v1/posts/**`, `/api/v1/metrics/**`, `POST /api/v1/import-posts`, `POST /api/v1/jobs/update-metrics` | `ADMIN` |
| Chưa đăng nhập | 401, `code` `UNAUTHORIZED` |
| Thiếu quyền | 403, `code` `FORBIDDEN` |
| Thiếu hoặc sai CSRF token ở request thay đổi dữ liệu | 403, `code` `FORBIDDEN` |

401/403 của API trả JSON cùng format lỗi; trang web thì chuyển về `/login` (khi có cách đăng nhập: form demo của profile `dev`, sau này OAuth2; chưa có thì 403). Lỗi nghiệp vụ không dùng `AccessDeniedException` để client nhận đúng câu báo lỗi.

API chỉ nhận CSRF token trong header `X-XSRF-TOKEN` (giá trị của cookie `XSRF-TOKEN`), không nhận trường `_csrf`, kể cả với upload multipart (#8): tìm trường `_csrf` buộc Tomcat parse body multipart ngay trong filter bảo mật, và body vượt giới hạn upload ném lỗi ở đó (log `ERROR` kèm stack trace cho mỗi request như vậy). Form của trang web (đăng nhập, đăng xuất, nút chạy job) vẫn gửi `_csrf`.

### Import bài viết (#6)

`POST /api/v1/import-posts`, `multipart/form-data`, trường `file` (`.xlsx`), cần CSRF token trong header.

| Kết quả | Status | Body |
|---|---|---|
| Thành công | 200 | `{batchId, totalRows, createdPosts, updatedPosts, snapshotsCreated, capturedAt}` |
| Có dòng lỗi (không ghi gì, Q4) | 422 `VALIDATION` | `errors`: `[{row, column, message}]`, `row` là số dòng trong Excel |
| Không phải file `.xlsx` đọc được, thiếu cột bắt buộc, sai tên cột | 400 `BAD_REQUEST` | |
| File quá giới hạn (5MB) | 413 `CONTENT_TOO_LARGE` | tên status của Spring 7 cho 413 |
| Quá số dòng cho phép (5.000) | 422 `VALIDATION` | `errors` rỗng, `detail` ghi giới hạn (đọc tới dòng dữ liệu thứ 5.001 thì dừng, không đếm hết file) |
| Một bài mới trong file vừa được request khác tạo, sau khi import đã kiểm tra | 409 `CONFLICT` | ràng buộc UNIQUE chặn, không ghi gì |
| Hai lần import cùng thêm các bài mới theo thứ tự ngược nhau (deadlock) | 409 `CONFLICT` | MySQL rollback bên thua, không ghi gì; gửi lại file là được |

Cột (đã chốt, Q3; tên cột không phân biệt hoa thường, `platform` thì phân biệt như API): `platform`, `externalId`, `accountName` bắt buộc; `url`, `content`, `publishedAt` tùy chọn; `likes`, `shares`, `comments`, `followers` tùy chọn nhưng có một thì phải có đủ cả bốn. Giới hạn độ dài giống DTO của `/posts`. Kiểm tra trùng `(platform, externalId)` trong cùng file.

- Gửi `IMPORT_COMPLETED` (#12) sau khi commit.
- File quá lớn phải nhận được 413 chứ không bị đứt kết nối: đặt `server.tomcat.max-swallow-size` lớn hơn giới hạn upload, test với file lớn hơn giới hạn thật (reviewer repo cũ đã nhắc).

### Xuất báo cáo (#7)

`GET /api/v1/export-report?from=2026-11-01&to=2026-11-30&platform=FACEBOOK` (mọi tham số tùy chọn; `from`/`to` là ngày `publishedAt` theo giờ Việt Nam, bao gồm cả hai đầu, tối đa 366 ngày).

- 200, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename="social-report-2026-11-20.xlsx"` (ngày xuất theo giờ Việt Nam).
- Nội dung theo đề xuất (Q11, chưa chốt): sheet "Posts" (mỗi bài kèm snapshot mới nhất, theo thứ tự tạo; cột như file import, thêm `capturedAt`; #7), sheet "Daily" (`date`, `platform`, `postsTracked`, `postsMeasured`, `likes`, `shares`, `comments`, `followers` của các ngày trong `from`..`to`; #12). #15 thêm cột chi phí quảng cáo quy ra VND và sheet tỷ giá (Q7).
- `from` sau `to`, khoảng quá dài, tham số sai kiểu (ngày không đúng dạng `yyyy-MM-dd`, nền tảng lạ) hoặc hơn 100.000 bài khớp bộ lọc: 422.

### Chạy job thủ công (#10, #11)

`POST /api/v1/jobs/update-metrics` (`ADMIN`, CSRF).

- 202 `{runId}`: đã bắt đầu, chạy nền.
- 409 `CONFLICT`: đang có lần chạy chưa xong (đã chốt, Q6).

`GET /api/v1/jobs/update-metrics/latest`: lần chạy gần nhất `{runId, status, triggerType, startedAt, finishedAt, accountsTotal, accountsFailed, snapshotsCreated, errorMessage}` và `lastUpdatedAt` (lần `SUCCEEDED`/`PARTIAL` gần nhất); 200 kèm `null` các trường khi chưa chạy lần nào.

### Dữ liệu biểu đồ (#13)

`GET /api/v1/chart-data?from=2026-11-14&to=2026-11-20&platform=TWITTER` (`from`/`to` là ngày theo giờ Việt Nam, bao gồm cả hai đầu, mặc định 7 ngày gần nhất, tối đa 366 ngày; `platform` tùy chọn).

```json
{
  "from": "2026-11-19",
  "to": "2026-11-20",
  "dates": ["2026-11-19", "2026-11-20"],
  "series": [
    {"platform": "FACEBOOK", "postsTracked": [4, 4], "postsMeasured": [4, 3], "likes": [1200, 1350], "shares": [80, 92], "comments": [40, 41], "followers": [25050, 25060]}
  ],
  "lastUpdatedAt": "2026-11-20T03:00:00Z"
}
```

- Mỗi mảng dài bằng `dates`, đọc từ `daily_platform_stats`. Mỗi số là trạng thái **tính đến hết ngày đó** (Q12, đã chốt), nên ngày thiếu snapshot không tụt về 0. `postsMeasured` < `postsTracked` nghĩa là ngày đó đo thiếu.
- Ngày không có dòng trong bảng (app tắt cả ngày) lấy dòng gần nhất trước đó của cùng nền tảng, `postsMeasured` là 0. Vì vậy truy vấn đọc thêm dòng gần nhất trước `from`. Trước dòng đầu tiên của nền tảng thì mọi số là 0.
- `from` sau `to` hoặc khoảng quá dài: 422.

### WebSocket (#13)

- Endpoint STOMP: `/ws` (SockJS). Chỉ user đã đăng nhập; frame `CONNECT` phải kèm CSRF token (lấy từ thẻ meta của trang).
- Topic `/topic/metrics`, event dạng `{type, occurredAt, …}`, không chứa số liệu (client gọi lại `/chart-data`):
  - `STATS_UPDATED` `{fromDate, toDate}`: thống kê các ngày trong khoảng này vừa được tính lại.
  - `JOB_FINISHED` `{runId, status, finishedAt}`: dashboard cập nhật "Last updated time".

### SOAP (#15)

- Endpoint giả lập (Spring-WS) trong app: `/soap/*`, WSDL tại `/soap/exchange-rates.wsdl`, operation lấy tỷ giá theo cặp tiền và ngày. Dùng tiền tố `/soap` vì `/ws` đã dành cho WebSocket.
- Client (`WebServiceTemplate`) đọc URL và timeout từ cấu hình. SOAP fault hoặc timeout khi dashboard cần tỷ giá thì dashboard hiện "không lấy được tỷ giá", không lỗi cả trang; nếu có API trả tỷ giá thì trả 502 `BAD_GATEWAY` cùng format lỗi.
- Dùng kết quả ở đâu (đã chốt, Q7 = b + c): dashboard hiện tỷ giá USD → VND và tổng chi phí quảng cáo quy ra VND; file export thêm cột chi phí quy ra VND và sheet tỷ giá. Bài viết có thêm `adCost` (USD, tùy chọn) ở `/posts` và file import.

### Trang web (Thymeleaf, #9, #11, #14)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/login` | Nút đăng nhập Facebook / X, thông báo lỗi nếu login thất bại |
| GET | `/oauth2/authorization/{facebook\|twitter}` | Bắt đầu OAuth2 (Spring Security cung cấp) |
| GET | `/login/oauth2/code/{facebook\|twitter}` | Callback OAuth2 (Spring Security cung cấp) |
| POST | `/logout` | Đăng xuất (CSRF) |
| GET | `/`, `/dashboard` | Dashboard: "Last updated time", trạng thái lần chạy gần nhất, biểu đồ, tỷ giá; admin thấy thêm nút chạy job và form import |
| POST | `/dashboard/update-metrics` | Form Thymeleaf (trường ẩn `_csrf`), `ADMIN`: chạy job, quay lại dashboard kèm thông báo "đã bắt đầu" hoặc "job đang chạy" |

- Đăng xuất và nút chạy job là form Thymeleaf submit thường (CSRF qua trường ẩn `_csrf`), đúng ý "CSRF token và form submission" của đề.
- Import và biểu đồ dùng JS: `fetch` gửi CSRF token trong header (không gửi `_csrf` trong body multipart). Nguồn token của từng loại client: `03-kien-truc.md`, mục 3.
