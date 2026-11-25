# Thiết kế API CRUD `/posts` và `/metrics`

Phạm vi: issue #4 (P1-03). Swagger UI (profile `dev`): http://localhost:8080/swagger-ui.html

## Endpoint

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

## Định dạng lỗi

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
| JSON hỏng, sai kiểu, enum lạ trong body (`"platform": "INSTAGRAM"`), id trên path không phải số | 400 | `BAD_REQUEST` |
| Sai method / sai `Content-Type` / path không tồn tại | 405 / 415 / 404 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` / `NOT_FOUND` |
| Lỗi không lường trước: không lộ chi tiết, `log.error` kèm exception | 500 | `INTERNAL_ERROR` |

- `GlobalExceptionHandler` kế thừa `ResponseEntityExceptionHandler` nên các lỗi của framework giữ đúng status. Lỗi validation được xử lý bằng cách override `handleMethodArgumentNotValid` (khai thêm `@ExceptionHandler` cho cùng exception sẽ báo "Ambiguous @ExceptionHandler" lúc khởi động). Với lỗi framework, `code` là tên của HTTP status.
- `message` trong `errors` là message mặc định của Bean Validation (tiếng Anh, Hibernate Validator không có bản tiếng Việt). Client nên dựa vào `field` và `code`.
- Body có danh sách thì dùng `List<@NotNull @Valid ...>` để phần tử `null` bị chặn ở validation. API ở issue này chưa có body dạng danh sách; quy tắc này áp dụng cho import ở #6.
- **Lưu ý cho #8 (Spring Security):** handler `Exception` chung sẽ biến `AccessDeniedException`/`AuthenticationException` ném từ controller (ví dụ `@PreAuthorize`) thành 500. Khi thêm security phải thêm handler riêng trả 403/401 cùng format, kèm test user thiếu quyền.
