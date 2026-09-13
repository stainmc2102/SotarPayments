# 🧩 Placeholder (PlaceholderAPI)

Yêu cầu cài **PlaceholderAPI** và đăng ký expansion:

```
/papi ecloud download SotarPayments
```

hoặc expansion sẽ tự đăng ký khi PlaceholderAPI được phát hiện lúc khởi động (identifier `kp`, alias `sotarpayments`).

> Cú pháp linh hoạt theo từ khóa (token-based) — plugin nhận diện placeholder qua các từ khóa chứa trong chuỗi, không phân biệt hoa/thường, nên nhiều biến thể viết khác nhau vẫn hoạt động. Bảng dưới liệt kê các dạng phổ biến nhất.

## Tổng nạp cá nhân

| Placeholder | Kết quả |
|---|---|
| `%kp_donate_total%` | Tổng nạp toàn thời gian của người chơi (đã định dạng, ví dụ `1.5M`) |
| `%kp_player_total%` | Tương đương `donate_total` |
| `%kp_donate_total_today%` | Tổng nạp trong ngày |
| `%kp_donate_total_week%` | Tổng nạp trong tuần |
| `%kp_donate_total_month%` | Tổng nạp trong tháng |
| `%kp_donate_total_year%` | Tổng nạp trong năm |
| `%kp_donate_total_raw%` | Tổng nạp dạng số thuần, không định dạng (thêm `raw`/`number`/`plain`/`unformatted`/`noformat` vào bất kỳ placeholder nào ở trên) |

## Tổng nạp toàn server

| Placeholder | Kết quả |
|---|---|
| `%kp_server_total%` | Tổng nạp toàn server, toàn thời gian |
| `%kp_donate_server_total_month%` | Tổng nạp toàn server trong tháng |
| `%kp_server_total_week%` | Tổng nạp toàn server trong tuần |

## Bảng xếp hạng (top)

Cú pháp chung: `top_<hạng>_<player|amount>[_<khoảng-thời-gian>]`

| Placeholder | Kết quả |
|---|---|
| `%kp_top_1_player%` | Tên người chơi hạng 1 toàn thời gian |
| `%kp_top_1_amount%` | Số tiền đã nạp của hạng 1 |
| `%kp_donate_top_3_player%` | Tên người chơi hạng 3 (cú pháp thay thế) |
| `%kp_top_1_player_month%` | Người dẫn đầu bảng xếp hạng tháng này |
| `%kp_top_1_amount_week%` | Số tiền của hạng 1 trong tuần |

> Nếu không có từ khóa `player`/`name` hay `amount`/`money`/..., placeholder mặc định trả về **số tiền**.

## Mốc nạp toàn server

| Placeholder | Kết quả |
|---|---|
| `%kp_mocnapserver%` | Trạng thái mốc nạp toàn server hiện tại |
| `%kp_mocnapserver_total%` | Tổng nạp hiện tại tính vào mốc server |

## Khác

| Placeholder | Kết quả |
|---|---|
| `%kp_transactions%` | Tổng số giao dịch đã ghi nhận trong hệ thống |

## Ví dụ dùng trong scoreboard/tab list

```yaml
lines:
  - "&fTổng nạp: &a%kp_donate_total%"
  - "&fHạng: &e#%kp_donate_top_rank%"
  - "&fTop tháng: &b%kp_top_1_player_month% &7(%kp_top_1_amount_month%)"
```

> Nếu có lỗi truy vấn cơ sở dữ liệu (mất kết nối, timeout...), các placeholder đã nhận diện được sẽ trả về `0` thay vì hiển thị nguyên chuỗi placeholder gây rối giao diện.
