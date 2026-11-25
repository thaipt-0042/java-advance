# Câu hỏi cần làm rõ

Các điểm đề bài chưa nói rõ. Mỗi câu ghi các phương án và **đề xuất**. **Q1, Q2, Q3, Q4, Q5, Q12 đã chốt**; các câu còn lại đang **chưa có câu trả lời**. Tài liệu thiết kế khác (`00`–`03`) đang viết theo đề xuất, chỗ nào phụ thuộc câu trả lời thì ghi số câu (Q…). Có câu trả lời thì cập nhật file này và các tài liệu liên quan.

Đề bài được nhắc theo tên giai đoạn ("Khởi tạo project", "Unit Test + Import/Export Excel", "Bảo mật & CSRF + Social Login", "Background Job & MultiThread", "JMS & Queue Handling", "WebSocket + Chart Visualization", "Spring WebService + Reflection nâng cao + Tổng kết").

## Q1. Ai là admin, user thường làm được gì?

Đề mô tả ứng dụng "giúp admin tổng hợp và theo dõi", nhưng chỉ có đăng nhập bằng Facebook/Twitter, không có đăng ký hay phân quyền. Ai đăng nhập được cũng là admin thì bất kỳ ai có tài khoản Facebook cũng sửa/xóa được dữ liệu.

- (a) Mọi người đăng nhập đều là admin.
- (b) Danh sách admin cấu hình qua biến môi trường (`APP_ADMIN_ACCOUNTS=FACEBOOK:<id>,TWITTER:<id>`); mỗi lần đăng nhập, role được đồng bộ theo danh sách này. Người khác là `USER`.
- (c) Admin đầu tiên cấu hình như (b), sau đó admin cấp quyền cho người khác trên giao diện.

**Đã chốt: (b).** `USER` chỉ xem: dashboard, biểu đồ, danh sách bài/snapshot, export. `ADMIN` thêm: tạo/sửa/xóa bài và snapshot, import, chạy job thủ công. Bảng `users.role` đã có từ #3.

## Q2. Đăng nhập khi phát triển và demo

Login Facebook/X cần tạo app trên trang developer của từng nền tảng (Facebook còn cần app review cho một số quyền, X cần tài khoản developer). Không có app thật thì không ai chạy thử được các màn hình sau đăng nhập.

- (a) Bắt buộc tạo app thật; client id/secret để trong `.env`.
- (b) Profile `dev` có thêm form login với một tài khoản demo (mật khẩu đọc từ `.env`), chỉ bật ở `dev`; production chỉ có OAuth2.
- (c) Chạy một OAuth2 server giả lập bằng Docker để luồng OAuth2 chạy đủ bước.

**Đã chốt: (b)** cho demo, cộng với (a) khi có app thật. Test tự động không cần cả hai: dùng `oauth2Login()` của `spring-security-test`.

## Q3. File Excel import có chứa số liệu không?

Giai đoạn "Unit Test + Import/Export Excel" ghi file chứa "danh sách bài viết"; giai đoạn JMS ghi import xong thì "cập nhật thống kê tổng hợp". Nếu file chỉ có bài viết thì import xong thống kê gần như không đổi (chỉ thêm bài chưa có số liệu).

- (a) File chỉ có thông tin bài: `platform`, `externalId`, `accountName`, `url`, `content`, `publishedAt`.
- (b) Thêm các cột số liệu **không bắt buộc**: `likes`, `shares`, `comments`, `followers`. Dòng có số liệu thì tạo thêm một snapshot; mọi snapshot của một file có chung `captured_at` = thời điểm import.

**Đã chốt: (b).** Có số liệu thì message `IMPORT_COMPLETED` mới thật sự làm thống kê thay đổi.

## Q4. Import: dòng lỗi và bài trùng

- Có dòng lỗi (sai kiểu, thiếu cột bắt buộc, quá dài):
  - (a) Bỏ cả file, trả danh sách lỗi theo số dòng Excel.
  - (b) Lưu các dòng đúng, trả danh sách dòng bị bỏ.
- Bài đã có (cùng `platform` + `externalId`):
  - (c) Cập nhật thông tin bài theo file.
  - (d) Bỏ qua dòng đó.
- Cùng một bài xuất hiện hai lần trong một file: báo lỗi dòng.

**Đã chốt: (a) + (c).** (a) đơn giản cho người dùng (sửa file rồi import lại, không phải đoán dòng nào đã vào) và message JMS chỉ gửi khi cả file đã vào DB. (c) cho phép sửa file rồi import lại. Giới hạn: file `.xlsx` tối đa 5MB, tối đa 5.000 dòng. Chi tiết khi làm #6: bài trùng so `externalId` như cột trong DB (không phân biệt hoa thường và dấu); chỉ cập nhật các cột có trong file, nên file thiếu cột `content` không xóa nội dung đã lưu.

## Q5. Bảng thống kê tổng hợp được tính lại khi nào?

"Thống kê tổng hợp" là một bảng tính sẵn (`daily_platform_stats`): mỗi ngày × nền tảng một dòng, ghi tổng likes/shares/comments/followers. Biểu đồ đọc bảng nhỏ này thay vì cộng lại toàn bộ snapshot mỗi lần vẽ. Bảng này chỉ đúng nếu được **tính lại mỗi khi snapshot thay đổi**.

Snapshot thay đổi ở ba chỗ: import Excel, job chạy mỗi giờ, và sửa/xóa qua API `/posts`, `/metrics`. Đề chỉ nói một chỗ: giai đoạn "JMS & Queue Handling" ghi import xong thì gửi `IMPORT_COMPLETED` để listener "cập nhật thống kê tổng hợp".

Ví dụ: 9:00 import file, bảng được tính lại. 10:00 job lấy được số liệu mới (likes tăng từ 100 lên 150). Nếu chỉ tính lại sau import thì biểu đồ vẫn hiện 100 cho tới lần import sau. Trong khi đó giai đoạn "WebSocket + Chart Visualization" lại ghi: "Khi có dữ liệu mới (metrics update) → gửi WebSocket event → Frontend nhận và update Chart.js". Biểu đồ được báo cập nhật, tải lại `/chart-data`, nhưng vẫn nhận số cũ.

- (a) Chỉ tính lại sau import (đúng chữ của giai đoạn JMS). Biểu đồ không đổi khi job chạy.
- (b) Tính lại sau import, sau mỗi lần chạy job, và sau khi sửa/xóa bài hoặc snapshot qua API. Cả ba gửi message vào cùng một queue, cùng một listener tính lại các ngày bị ảnh hưởng.

**Đã chốt: (b)**, để khớp với giai đoạn "WebSocket + Chart Visualization". Message import vẫn là `IMPORT_COMPLETED` như đề; job gửi `METRICS_UPDATED`, API gửi `METRICS_CHANGED`. Cách tính từng ô của bảng: xem Q12.

## Q6. Job cập nhật số liệu

- Tài khoản nào được cập nhật? **Đề xuất**: mọi cặp `(platform, account_name)` đang có bài trong `posts`; mỗi tài khoản là một tác vụ chạy song song.
- "Mock API từ FB/TW": **đề xuất** một interface client, bản mock trả số liệu tăng dần ngẫu nhiên, có thể cấu hình độ trễ và tỷ lệ lỗi để thử xử lý lỗi. Không gọi API thật, nên token đã lưu (#9) chưa được dùng để gọi API ở phạm vi đề.
- Bấm "chạy ngay" khi job đang chạy: **đề xuất** trả 409 "job đang chạy", không xếp hàng.

## Q7. Dùng kết quả gọi SOAP vào đâu?

Đề ghi "Gọi 1 WebService mock (ví dụ: exchange rate API SOAP)" nhưng không nói dùng kết quả để làm gì; dữ liệu của app không có tiền tệ.

- (a) Chỉ hiển thị tỷ giá (ví dụ USD → VND) trên dashboard.
- (b) (a) và thêm một sheet tỷ giá vào file export.
- (c) Thêm dữ liệu chi phí quảng cáo cho bài viết để quy đổi tiền tệ (mở rộng phạm vi).

**Đề xuất: (a).** SOAP endpoint giả lập nằm ngay trong app (`/soap/*`), client đọc URL từ cấu hình nên trỏ sang dịch vụ khác được.

## Q8. Token truy cập có cần mã hóa khi lưu không?

Đề yêu cầu "Lưu token truy cập". Spring Security mặc định lưu token dạng rõ trong DB.

- (a) Lưu dạng rõ, chỉ đảm bảo không log, không trả ra API/export.
- (b) Mã hóa bằng khóa đọc từ biến môi trường.

**Đề xuất: (a)** trong phạm vi bài tập (token không được dùng để gọi API thật, xem Q6), ghi rõ rủi ro trong README.

## Q9. Giao diện: Thymeleaf hay ReactJS?

Đề ghi "Frontend (optional): Thymeleaf or ReactJS minimal UI", nhưng các giai đoạn lại cần trang thật: login → "redirect về dashboard", "CSRF token và form submission", màn hình "Last updated time", Chart.js.

**Đề xuất: Thymeleaf**, render từ cùng app (cùng session đăng nhập, CSRF token có sẵn trong form), JS thuần cho Chart.js và WebSocket.

## Q10. Broker JMS: ActiveMQ hay RabbitMQ?

Đề cho chọn một. RabbitMQ dùng giao thức AMQP; muốn dùng qua JMS phải thêm thư viện client JMS riêng. ActiveMQ Artemis hỗ trợ JMS trực tiếp và có starter của Spring Boot.

**Đề xuất: ActiveMQ Artemis**, chạy bằng Docker Compose; test dùng Testcontainers.

## Q11. File export gồm những gì?

Đề ghi "API `/export-report` (export Excel)" và ở giai đoạn cuối "Dùng Reflection để tự động xuất model thành Excel".

**Đề xuất**:
- `/export-report` (#7): một sheet các bài viết (lọc theo nền tảng, khoảng ngày `publishedAt` theo giờ Việt Nam), mỗi bài kèm snapshot mới nhất; một sheet thống kê theo ngày từ `daily_platform_stats`.
- #16 làm lại phần đọc/ghi Excel thành generic: một exporter/importer cho mọi class có annotation `@ExcelColumn`, dùng chung cho import (#6) và export (#7).

## Q12. Bài không có snapshot trong ngày thì tính số nào?

likes, shares, comments, followers là **tổng tích lũy** tại lúc đo ("bài này đang có 120 likes"), không phải số phát sinh trong ngày. Snapshot thì không ngày nào cũng có: job lỗi, app tắt, hoặc bài chỉ được import một lần.

Ví dụ: Facebook có hai bài A và B.

| | Ngày 1 | Ngày 2 | Ngày 3 |
|---|---|---|---|
| Snapshot của A | 100 likes | không có (job lỗi) | 120 likes |
| Snapshot của B | 50 likes | 60 likes | 70 likes |

Nếu mỗi ngày chỉ tính từ **snapshot trong ngày đó** (thiết kế ban đầu) thì tổng likes Facebook ngày 2 = 60 (chỉ có B), biểu đồ vẽ 150 → 60 → 190: như thể A mất hết likes rồi lấy lại. Cả nền tảng không có snapshot nào trong ngày thì còn tệ hơn: issue #13 ghi trả về 0, biểu đồ rơi về 0.

- (a) Chỉ tính snapshot trong ngày; ngày không có dòng nào vẽ 0 (đúng chữ issue #13). Biểu đồ trên: 150 → 60 → 190.
- (b) Chỉ tính snapshot trong ngày; ngày không có dòng nào trả `null`, Chart.js vẽ đứt đoạn. Không sửa được ngày 2 ở ví dụ trên (chỉ thiếu A, không thiếu cả ngày).
- (c) Đổi cách tính: mỗi bài lấy **snapshot mới nhất tính đến hết ngày đó** (không cần nằm trong ngày). Ngày 2 lấy A = 100 (của ngày 1), tổng = 160; biểu đồ 150 → 160 → 190. Followers cũng vậy, theo tài khoản. Bài chưa có snapshot nào thì chưa được tính, đúng là 0.

**Đã chốt: (c).** Đúng bản chất số tích lũy, và xử lý được cả hai trường hợp: thiếu một vài bài lẫn thiếu cả ngày.
- Ngày không có dòng nào trong bảng (app tắt cả ngày) thì không có snapshot mới, nên giá trị đúng bằng dòng gần nhất trước đó; `/chart-data` lấy dòng đó, không vẽ 0.
- Thêm cột `posts_measured`: số bài có snapshot trong chính ngày đó, để biết ngày nào đo thiếu.
- Đổi lại, sửa/xóa một snapshot ngày `D` thì mọi ngày từ `D` tới hôm nay đều phải tính lại. Import và job luôn ghi snapshot "bây giờ", nên thường chỉ tính lại hôm nay; chỉ sửa snapshot cũ qua API mới phải tính nhiều ngày.

Đã cập nhật định nghĩa `daily_platform_stats` ở `01-thiet-ke-csdl.md`, `/chart-data` ở `02-thiet-ke-api.md` và issue #13.

## Cần báo mentor

Những chỗ làm khác chữ của đề, có lý do:

1. **Spring Boot 4.1 thay vì 3.x**: dòng 3.5 hết hỗ trợ bản miễn phí từ 30/06/2026; 4.1 được hỗ trợ tới 07/2027. Kéo theo Hibernate 7, Jackson 3, Spring Security 7.
2. **Đường dẫn API có tiền tố `/api/v1`**: `/api/v1/posts`, `/api/v1/metrics`, và dự kiến `/api/v1/import-posts`, `/api/v1/export-report`, `/api/v1/chart-data`. Tách API (JSON, trả 401/403 dạng JSON) khỏi trang web (Thymeleaf, chuyển về trang login) trong cấu hình bảo mật.
3. **Thêm Flyway và Testcontainers** (đề không nhắc): schema nằm trong migration và Hibernate chỉ `validate`; test chạy trên MySQL thật để kiểm được ràng buộc của MySQL.
4. **Tên class**: đề gọi `MetricService`, code dùng `SocialMetricService` cho khớp entity `SocialMetric`.
