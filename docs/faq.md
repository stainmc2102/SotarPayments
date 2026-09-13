# ❓ Câu hỏi thường gặp

### Plugin không load, báo lỗi `Unsupported API version`?

SotarPayments yêu cầu **Paper/Folia 1.21+** và **Java 21+**. Server Spigot/Bukkit thuần hoặc phiên bản cũ hơn không được hỗ trợ (giao diện Dialog và scheduler Folia cần API mới).

### Đổi cơ sở dữ liệu (SQLite → MySQL) nhưng dữ liệu không cập nhật?

Thay đổi `database.type` trong `database.yml` **không** áp dụng qua `/sotar-admin reload` — bạn phải **khởi động lại server**. Nếu muốn chuyển dữ liệu cũ sang backend mới, dùng lệnh `/sotar-admin migrate-database` trước khi đổi.

### Nạp ngân hàng không tự cộng tiền dù đã chuyển khoản đúng nội dung?

Kiểm tra theo thứ tự:

1. `providers/sepay.yml` (hoặc `payos.yml`) đã điền đúng `api-token`/`api-key` chưa.
2. `payments.yml` → `napbank.poll-every-seconds` — plugin đối soát định kỳ, không phải tức thì; đợi tối đa 1 chu kỳ.
3. Nội dung chuyển khoản phải khớp mẫu `payment-format` (mặc định `{playername} TENCUM {ordercode}`) — sai định dạng sẽ không đối soát được.
4. Chạy `/sotar-admin status` để kiểm tra provider có đang kết nối bình thường không.
5. Bật `logging.debug: true` trong `config.yml` rồi xem log console/`transactions.log` để tìm lỗi cụ thể.

### Nạp thẻ cào báo sai mệnh giá/nhà mạng dù đã đúng?

Nếu `payments.yml` → `napthe.validation.restrict-to-gui-options: true`, plugin **chỉ** chấp nhận đúng các lựa chọn khai báo trong `napthe.gui` (hoặc theo từng provider trong `providers/`). Thêm mệnh giá/nhà mạng còn thiếu vào đó, hoặc đặt về `false` để cho phép nhập tự do như trước.

### Kinh tế không cộng tiền, dù giao dịch thành công?

Kiểm tra `config.yml` → `providers.economy`:

- `vault`/`playerpoints`/`fancyeco`: chắc chắn plugin kinh tế tương ứng đã cài và hoạt động.
- `auto`: tự chọn theo thứ tự FancyEco → PlayerPoints → Vault → command; nếu tất cả đều không khả dụng sẽ dùng `reward-commands`.
- `command`: kiểm tra lệnh trong `payments.yml` → `economy.reward-commands` có chạy đúng cú pháp console không (thử chạy tay bằng console).

Khi phát thưởng qua Vault/PlayerPoints/FancyEco thất bại, plugin sẽ tự fallback sang `reward-commands` nếu `economy.fallback-to-command: true`.

### Bot Discord Auto Buy không lên online?

1. Kiểm tra `store.yml` → `settings.enabled: true` và token hợp lệ (chưa bị reset trên Developer Portal).
2. Nếu dùng biến môi trường (`bot-token-environment-variable`), đảm bảo biến đã được `export` **trước khi** khởi động server (đặt trong file khởi động/systemd/service, không chỉ terminal tạm).
3. Chạy `/taokenhbanhang status` để xem log lỗi kết nối cụ thể.
4. Kiểm tra bot đã được mời vào đúng server và có đủ quyền gửi tin nhắn/embed tại các kênh đã cấu hình ID.

### Làm sao đổi ngôn ngữ giao dịch của plugin?

Sửa `config.yml` → `language` (ví dụ `en`, `vi`, `ja`...) rồi `/sotar-admin reload`. Danh sách đầy đủ tại [docs/configuration.md](configuration.md#languages).

### Muốn tắt gửi thống kê bStats?

Đặt `metrics.enabled: false` trong `config.yml`, hoặc tắt toàn cục qua `plugins/bStats/config.yml` (`enabled: false`) áp dụng cho mọi plugin dùng bStats trên server.

### Chưa thấy câu trả lời cần tìm?

Tham gia Discord hỗ trợ: **[discord.gg/jD5naBEGMA](https://discord.gg/jD5naBEGMA)** hoặc mở [GitHub Issue](../../issues) kèm log lỗi (bật `logging.debug: true` trước khi lấy log).
