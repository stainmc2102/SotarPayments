# 🛠️ Cấu hình

Toàn bộ cấu hình được chia nhỏ theo chức năng để dễ quản lý. Sau khi sửa bất kỳ file nào bên dưới (**trừ `database.yml`**), chạy `/sotar-admin reload` để áp dụng.

## `config.yml` — Cấu hình chung

Nơi duy nhất bạn cần sửa để **chọn provider**:

```yaml
providers:
  bank: "sepay"       # sepay | payos
  card: "card2k"       # card2k | gachthefast | thesieure
  economy: "command"   # command | auto | playerpoints | vault | fancyeco
```

- `economy: auto` sẽ tự ưu tiên theo thứ tự: **FancyEco → PlayerPoints → Vault → command**.
- Ngoài ra còn có:
  - `language`: ngôn ngữ giao diện mặc định (xem [danh sách ngôn ngữ](#languages)).
  - `support.discord-url`: link hiển thị ở banner console và lệnh `/sotarpayments`.
  - `logging`: bật/tắt log console, log file giao dịch, log chống spam.
  - `interface.prefer-dialog`: ưu tiên Dialog UI hiện đại khi server hỗ trợ (Paper/Leaf mới); tự quay về GUI + chat nếu không có.
  - `metrics.enabled`: bật/tắt gửi thống kê ẩn danh bStats.

## `database.yml` — Cơ sở dữ liệu

Hỗ trợ 4 backend: `sqlite` (mặc định), `mysql`, `mariadb`, `postgresql`.

```yaml
database:
  type: "sqlite"          # sqlite | mysql | mariadb | postgresql
  sqlite:
    file: "database.sqlite"
  remote:
    host: "127.0.0.1"
    port: 0                 # 0 = tự chọn: 3306 (MySQL/MariaDB), 5432 (PostgreSQL)
    database: "sotarpayments"
    username: "sotarpayments"
    password: ""
    use-ssl: false
  pool:                     # Cấu hình HikariCP
    maximum-pool-size: 10
    minimum-idle: 1
```

> ⚠️ **Đổi `database.type` cần khởi động lại server** — không áp dụng qua lệnh reload. Sao lưu `database.sqlite` (hoặc database từ xa) trước khi đổi backend; plugin không tự xóa hay ghi đè dữ liệu cũ.

## `payments.yml` — Mức nạp, thuế, khuyến mãi, kinh tế

### Nạp ngân hàng (`napbank`)

```yaml
napbank:
  min-amount: 2000
  max-amount: 0             # 0 = không giới hạn
  timeout-seconds: 600      # thời gian chờ thanh toán trước khi hủy giao dịch
  poll-every-seconds: 10    # tần suất đối soát giao dịch
  gui:
    amounts: [10000, 20000, ...]   # các mức tiền hiển thị trong GUI
  promotion:
    enabled: true
    percent: 20
    end-date: "31/05/2026 23:59:59"
```

### Nạp thẻ cào (`napthe`)

```yaml
napthe:
  taxes:
    enabled: true
    rates:
      "10000": 16    # % thuế theo từng mệnh giá
  validation:
    restrict-to-gui-options: false  # true = chỉ chấp nhận mệnh giá/nhà mạng khai báo sẵn
  security:
    mask-sensitive-confirmation: true  # che một phần số thẻ/seri khi xác nhận
  rewards:
    ratio: 1000     # tỉ lệ quy đổi tiền thẻ → điểm/tiền game
```

### Kinh tế (`economy`)

```yaml
economy:
  fallback-to-command: true
  reward-commands:
    - "p give {player} {points}"
  post-commands:
    bank: []
    manual: []
    card:
      - "bc &bSotarPayments &8» &f{player} vừa nạp &a{amount} VNĐ &fqua thẻ cào."
```

- `reward-commands` chạy tuần tự bằng console, dùng làm phần thưởng chính khi provider kinh tế là `command`, hoặc làm **fallback** khi Vault/PlayerPoints/FancyEco phát thưởng thất bại.
- `post-commands` chạy **sau khi** phần thưởng chính thành công, tách riêng theo kênh nạp (`bank`, `card`, `manual`) — phù hợp cho broadcast, crate, cấp rank...
- Placeholder hỗ trợ: `{player}`, `{points}`, `{amount}`, `{net_amount}`, `{channel}` (cú pháp cũ `%player%`... vẫn hoạt động).

## `providers/*.yml` — API nhà cung cấp

Mỗi provider có file riêng, chỉ cần điền thông tin của **provider đang chọn** trong `config.yml`:

| File | Provider | Trường cần điền |
|---|---|---|
| `sepay.yml` | Ngân hàng qua SePay | `api-token`, `bank-code`, `bank-name`, `account-number`, `account-name` |
| `payos.yml` | Ngân hàng qua PayOS | `client-id`, `api-key`, `checksum-key` |
| `card2k.yml` | Thẻ cào Card2K | `partner_id`, `partner_key` |
| `gachthefast.yml` | Thẻ cào GachTheFast | `partner_id`, `partner_key`, `wallet_id` |
| `thesieure.yml` | Thẻ cào Thesieure | `partner_id`, `partner_key` |

> Mã ngân hàng (`bank-code`) dùng cho sinh VietQR — tra cứu tại trang hỗ trợ SePay hoặc ngân hàng của bạn.

## `discord.yml` — Webhook giao dịch

```yaml
discord-webhook:
  enabled: false
  url: "DAN_LINK_WEBHOOK_DISCORD_CUA_BAN_VAO_DAY"
  username: "SotarPayments"
  transactions:
    bank:   { enabled: true, color: 65280 }
    card:   { enabled: true, color: 16776960 }
    manual: { enabled: true, color: 10181046 }
```

Gửi thông báo real-time về kênh Discord mỗi khi có giao dịch nạp bank/thẻ/thủ công thành công, màu embed tùy chỉnh riêng từng loại. Khác với **Discord Auto Buy** (xem [docs/discord-store.md](discord-store.md)) — đây chỉ là webhook thông báo một chiều, không cần bot token.

## `milestones.yml` — Mốc nạp

```yaml
milestones:
  '500000':
    rewards:
      - "give %player% diamond 32"
      - "broadcast &bSotarPayments &8» &f%player% vừa đạt mốc nạp &a500,000 VNĐ."

server-milestones:
  enabled: true
  active-target: 1000000
```

- `milestones`: quà theo tổng nạp **cá nhân**, key là số tiền (VNĐ).
- `server-milestones`: mốc nạp **toàn server**, có cơ chế chống clone tài khoản để farm mốc.

## `gui.yml` — Giao diện kho đồ

Cấu hình chi tiết từng menu (`card-provider`, `card-amount`, `bank-amount`): kích thước, tiêu đề, item cố định/động, slot, material, lore, action... Có sẵn sơ đồ slot và hướng dẫn trong comment đầu file. Hỗ trợ mã màu cũ (`&a`) lẫn hex (`&#RRGGBB`) và biến ngôn ngữ `{lang:gui.xxx}`.

## `store.yml` — Discord Auto Buy

Xem tài liệu riêng: [docs/discord-store.md](discord-store.md)

## <a name="languages"></a>`languages/*.yml` — Đa ngôn ngữ

18 ngôn ngữ có sẵn: `vi`, `en`, `zh`, `ja`, `ko`, `th`, `id`, `ms`, `tl`, `ru`, `tr`, `ar`, `hi`, `fr`, `de`, `es`, `pt`, `pl`. Chọn ngôn ngữ mặc định tại `config.yml` → `language`, hoặc để plugin khác quản lý theo từng người chơi qua tích hợp riêng (nếu có).
