# ⚙️ Cài đặt

## 1. Yêu cầu hệ thống

| Thành phần | Yêu cầu |
|---|---|
| Server | Paper hoặc Folia **1.21.x** trở lên |
| Java | **21** trở lên |
| Cơ sở dữ liệu | SQLite (mặc định, không cần cài thêm) hoặc MySQL/MariaDB/PostgreSQL |
| Plugin tùy chọn | PlaceholderAPI, Vault, PlayerPoints, FancyEconomy/FancyEconomyCore |

> Plugin **không hỗ trợ** Spigot/Bukkit thuần hoặc phiên bản Minecraft cũ hơn 1.21 — giao diện Dialog hiện đại và scheduler Folia cần API mới của Paper.

## 2. Tải plugin

- Tải file `SotarPayments-<phiên bản>.jar` từ trang phát hành (Releases / SpigotMC / BuiltByBit), **hoặc**
- Tự build từ mã nguồn:

  ```bash
   git clone https://github.com/stainmc21022/-t.git
   cd -t
   mvn -B clean package
  ```

  File jar hoàn chỉnh nằm tại `target/SotarPayments-1.4.3.jar` (đã đóng gói sẵn toàn bộ thư viện cần thiết, không cần tải thêm gì khác).

## 3. Cài vào máy chủ

1. Copy file `.jar` vào thư mục `plugins/` của máy chủ.
2. Khởi động (hoặc restart) máy chủ **một lần** để plugin tự sinh cấu trúc thư mục dữ liệu tại `plugins/SotarPayments/`:

   ```
   plugins/SotarPayments/
   ├── config.yml            # Cấu hình chung, chọn provider
   ├── database.yml          # Cấu hình cơ sở dữ liệu
   ├── payments.yml          # Mức nạp, thuế, khuyến mãi, kinh tế
   ├── discord.yml           # Webhook thông báo giao dịch
   ├── milestones.yml        # Mốc nạp cá nhân / toàn server
   ├── gui.yml                # Giao diện kho đồ nạp bank/thẻ
   ├── store.yml              # Cấu hình Discord Auto Buy
   ├── providers/
   │   ├── sepay.yml
   │   ├── payos.yml
   │   ├── card2k.yml
   │   ├── gachthefast.yml
   │   └── thesieure.yml
   └── languages/
       ├── vi.yml, en.yml, ...
   ```

3. Dừng máy chủ (khuyến nghị) và điền thông tin cần thiết:
   - Chọn provider tại `config.yml` → mục `providers`.
   - Điền API key/tài khoản tương ứng trong `providers/<tên-provider>.yml`.
   - Cấu hình cơ sở dữ liệu tại `database.yml` nếu không dùng SQLite mặc định.
4. Khởi động lại máy chủ.

> ⚠️ Đổi `database.type` trong `database.yml` **luôn cần khởi động lại** máy chủ (không áp dụng qua `/sotar-admin reload`). Plugin không tự xóa hoặc ghi đè `database.sqlite` cũ khi đổi backend — hãy sao lưu trước khi thao tác.

## 4. Kiểm tra hoạt động

```
/sotar-admin status
```

Lệnh này hiển thị provider đang dùng, trạng thái kết nối cơ sở dữ liệu, và trạng thái bot Discord (nếu bật Discord Auto Buy).

Sau khi sửa bất kỳ file cấu hình nào (trừ `database.yml`), chạy:

```
/sotar-admin reload
```

## 5. Bước tiếp theo

- [Cấu hình chi tiết từng file YAML](configuration.md)
- [Danh sách lệnh & quyền](commands.md)
- [Thiết lập Discord Auto Buy](discord-store.md)
- [Câu hỏi thường gặp](faq.md)
