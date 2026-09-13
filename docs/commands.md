# 💬 Lệnh & quyền

## Lệnh cho người chơi

| Lệnh | Alias | Cú pháp | Mô tả |
|---|---|---|---|
| `/bank` | `napbank`, `banking` | `/bank [số tiền\|gui\|cancel]` | Nạp tiền qua ngân hàng (sinh mã VietQR, đối soát tự động) |
| `/napthe` | `napcard`, `card`, `thecao` | `/napthe [gui\|loại-thẻ mệnh-giá [seri] [mã-thẻ]]` | Nạp thẻ cào qua provider đã cấu hình |
| `/confirmcard` | — | `/confirmcard` | Xác nhận giao dịch nạp thẻ cào đang chờ |
| `/cancelcard` | — | `/cancelcard` | Hủy giao dịch nạp thẻ cào đang chờ |
| `/topnap` | — | `/topnap [all\|week\|month] [trang]` | Xem bảng xếp hạng nạp |
| `/lichsunap` | — | `/lichsunap` | Xem lịch sử nạp cá nhân |
| `/mocnap` | — | `/mocnap <canhan\|server\|bossbar>` | Xem quà mốc nạp cá nhân / toàn server / bật bossbar mốc server |
| `/sotarpayments` | `kp`, `sotar` | `/sotarpayments` | Thông tin plugin, phiên bản, link hỗ trợ |

## Lệnh quản trị

| Lệnh | Alias | Quyền | Mô tả |
|---|---|---|---|
| `/sotar-admin` | `kp-admin` | `sotarpayments.admin` | Lệnh gốc quản trị (xem các subcommand bên dưới) |
| `/taokenhbanhang` | `storepost`, `kpstore` | `sotarpayments.admin` | `/taokenhbanhang [publish\|reload\|status]` — gửi/quản lý embed cửa hàng Discord Auto Buy |

### Subcommand của `/sotar-admin`

| Subcommand | Chức năng |
|---|---|
| `help` | Hiển thị trợ giúp (mặc định khi gõ không tham số) |
| `gui` / `menu` / `dashboard` | Mở bảng điều khiển quản trị dạng GUI |
| `reload` | Tải lại toàn bộ cấu hình YAML (trừ `database.yml`) |
| `status` | Xem trạng thái provider, database, bot Discord |
| `napthucong` / `manual` | Cộng tiền/điểm thủ công cho người chơi |
| `lichsunap` / `history` | Tra cứu lịch sử nạp của người chơi bất kỳ |
| `mocnap` / `milestone` / `moc` | Quản lý mốc nạp |
| `reset topnap confirm` | Reset bảng xếp hạng nạp (yêu cầu xác nhận `confirm`) |
| `migrate-database` / `migratedb` | Di chuyển dữ liệu giữa các loại cơ sở dữ liệu |

## Bảng quyền (permissions)

| Quyền | Mặc định | Áp dụng cho |
|---|---|---|
| `sotarpayments.default` | `true` (mọi người chơi) | Toàn bộ lệnh nạp bank/thẻ, xem top, lịch sử, mốc nạp |
| `sotarpayments.admin` | `op` | Lệnh `/sotar-admin` và `/taokenhbanhang` |

> Nếu server dùng LuckPerms hoặc plugin permission khác, gán trực tiếp hai node trên cho từng nhóm — plugin không cần permission riêng theo lệnh con.

## Mẹo dùng nhanh

- Gõ `/bank gui` hoặc `/napthe gui` để mở giao diện chọn mức nạp thay vì gõ tay từng tham số.
- Trên Paper/Leaf hỗ trợ Dialog API, giao diện nạp sẽ tự chuyển sang dạng Dialog hiện đại (cấu hình tại `interface.prefer-dialog` trong `config.yml`).
- `/mocnap bossbar` bật/tắt thanh bossbar hiển thị tiến độ mốc nạp toàn server cho người chơi hiện tại.
