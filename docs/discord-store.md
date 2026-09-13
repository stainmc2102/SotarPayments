# 🛍️ Discord Auto Buy

Discord Auto Buy cho phép bán vật phẩm trực tiếp trên Discord thông qua embed + nút bấm, tách biệt hoàn toàn khỏi webhook thông báo giao dịch (`discord.yml`). Toàn bộ cấu hình nằm trong `store.yml`.

## 1. Tạo bot Discord

1. Vào [Discord Developer Portal](https://discord.com/developers/applications) → **New Application**.
2. Vào tab **Bot** → **Add Bot** → copy **Token**.
3. Bật các quyền **Privileged Gateway Intents** cần thiết (Server Members Intent nếu dùng vai trò staff theo ID).
4. Mời bot vào server bằng link OAuth2 với quyền tối thiểu: gửi tin nhắn, gửi embed, quản lý tin nhắn, sử dụng Slash Command.

## 2. Cấu hình `store.yml`

```yaml
settings:
  enabled: true
  bot-token: "DAN_TOKEN_BOT_DISCORD_CUA_BAN_VAO_DAY"
  bot-token-environment-variable: ""   # khuyến nghị production, xem bên dưới

  storefront-channel-id: "..."      # kênh hiển thị embed bán hàng
  purchase-log-channel-id: "..."    # kênh log đơn đã thanh toán & giao hàng
  feedback-channel-id: "..."        # kênh nhận đánh giá sau mua
  error-log-channel-id: "..."       # kênh log đơn lỗi/đơn treo
  audit-log-channel-id: "..."       # kênh audit log mọi hoạt động

  admin-role-ids: []      # role staff được dùng lệnh quản trị trên Discord
```

### Bảo mật token bằng biến môi trường (khuyến nghị)

Thay vì dán token thẳng vào `bot-token`, đặt biến môi trường trên máy chủ (ví dụ `SOTARPAYMENTS_DISCORD_TOKEN`) rồi điền **tên biến** vào `bot-token-environment-variable`. Khi biến tồn tại và có giá trị, plugin sẽ ưu tiên dùng nó thay vì giá trị trong file — tránh lộ token khi chia sẻ file cấu hình hoặc đẩy lên git.

```bash
export SOTARPAYMENTS_DISCORD_TOKEN="token-bot-cua-ban"
```

```yaml
settings:
  bot-token-environment-variable: "SOTARPAYMENTS_DISCORD_TOKEN"
```

### Người có quyền Administrator hoặc Manage Server trên Discord luôn được phép dùng lệnh quản trị của Store, không cần thêm vào `admin-role-ids`.

## 3. Đăng cửa hàng

Trong Minecraft (yêu cầu quyền `sotarpayments.admin`):

```
/taokenhbanhang publish   # gửi embed cửa hàng lên kênh storefront
/taokenhbanhang reload    # tải lại cấu hình store.yml + đăng ký lại slash command
/taokenhbanhang status    # kiểm tra trạng thái kết nối bot
```

Trên Discord, staff có quyền phù hợp cũng có thể dùng slash command `/taokenhbanhang` và `/sotar-admin kiemtramadon <ma-don>` để tra cứu đơn hàng theo mã.

## 4. Luồng hoạt động

1. Người chơi bấm nút mua trên embed cửa hàng → bot mở modal nhập thông tin cần thiết.
2. Đơn hàng được ghi nhận, khách thanh toán theo hướng dẫn của bot.
3. Sau khi xác nhận thanh toán, vật phẩm được giao tự động và đơn được log vào kênh `purchase-log-channel-id`.
4. Bot gửi lời mời đánh giá — phản hồi (chỉ gồm tên vật phẩm, số tiền, mã đơn, số sao) được gửi vào `feedback-channel-id`.
5. Mọi thao tác của staff và hệ thống được ghi vào `audit-log-channel-id`; đơn lỗi/đơn treo được cảnh báo tại `error-log-channel-id`.

## 5. Cố định slash command vào một server

Nếu muốn slash command của bot chỉ hiện tại một server Discord cụ thể (đăng ký nhanh hơn thay vì chờ đồng bộ toàn cục), điền guild/server ID vào trường tương ứng trong `store.yml`. Để trống, plugin sẽ tự ưu tiên guild chứa kênh bán hàng (hoặc guild duy nhất mà bot đang tham gia).

## Sự khác biệt với webhook giao dịch (`discord.yml`)

| | `discord.yml` (Webhook) | `store.yml` (Discord Auto Buy) |
|---|---|---|
| Mục đích | Thông báo giao dịch nạp bank/thẻ/thủ công | Bán hàng trực tiếp trên Discord |
| Cần bot token? | Không (chỉ cần Webhook URL) | Có |
| Tương tác 2 chiều? | Không | Có (nút bấm, modal, slash command) |
