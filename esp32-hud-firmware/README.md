# HUD Helmet - ESP32 Firmware

Firmware cho thiết bị HUD (Heads-Up Display) trên mũ bảo hiểm, sử dụng ESP32 + màn hình TFT ST7735 0.96 inch (160x80).

## Tính năng

- 📡 Kết nối WiFi (STA mode) để nhận dữ liệu từ điện thoại
- ⏰ Hiển thị giờ, phút, giây (cập nhật realtime)
- 📅 Hiển thị ngày, tháng, năm, thứ
- 🔔 Hiển thị thông báo từ điện thoại (tự động ẩn sau timeout)
- 📶 Hiển thị trạng thái WiFi
- 🎨 Giao diện HUD tối giản, dễ đọc khi lái xe

## Phần cứng cần thiết

- ESP32 DevKit V1 (hoặc tương đương)
- Màn hình TFT 0.96 inch ST7735S (160x80, SPI)

### Sơ đồ kết nối

| TFT Pin | ESP32 GPIO | Mô tả |
|---------|-----------|-------|
| VCC | 3.3V | Nguồn |
| GND | GND | Mass |
| SCL | GPIO 18 | SPI Clock |
| SDA | GPIO 23 | SPI MOSI |
| RES | GPIO 4 | Reset |
| DC | GPIO 2 | Data/Command |
| CS | GPIO 5 | Chip Select |
| BLK | GPIO 15 | Backlight |

## Build & Flash (Phần cứng thật)

### Yêu cầu
- ESP-IDF v5.0 trở lên
- Python 3.8+

### Các bước

```bash
# Cấu hình WiFi (SSID và Password)
idf.py menuconfig
# Vào "HUD Helmet Configuration":
#   - Display type: ST7735 (mặc định)
#   - WiFi SSID/Password: đổi theo mạng nhà bạn

# Build
idf.py build

# Flash (thay đổi COM port phù hợp)
idf.py -p COM3 flash

# Monitor log
idf.py -p COM3 monitor
```

## 🎮 Wokwi Simulator (Test không cần phần cứng)

Hỗ trợ mô phỏng trên Wokwi Simulator bằng ILI9341 (240x320) thay cho ST7735.

### Yêu cầu
- VS Code + Extension **Wokwi for VS Code** (có license)
- ESP-IDF v5.0+

### Các bước chạy Wokwi

```bash
# Bước 1: Copy cấu hình Wokwi
copy sdkconfig.defaults.wokwi sdkconfig.defaults

# Bước 2: Xóa sdkconfig cũ (nếu có) để tái tạo
del sdkconfig

# Bước 3: Build cho Wokwi
idf.py build

# Bước 4: Trong VS Code, nhấn F1 → "Wokwi: Start Simulator"
```

### Khác biệt giữa Wokwi và phần cứng thật

| | Phần cứng thật | Wokwi Simulator |
|---|---|---|
| Display | ST7735 160×80 | ILI9341 240×320 |
| WiFi | Mạng nhà (WPA2) | Wokwi-GUEST (open) |
| Backlight | GPIO 15 | Không có |
| Text scale | 1-2x | 2-4x (tự động) |

> **Lưu ý:** Sau khi test trên Wokwi xong, copy lại `sdkconfig.defaults` gốc và rebuild để flash lên phần cứng thật.

## API Endpoints

ESP32 chạy HTTP server trên port 80:

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| POST | `/api/time` | Cập nhật thời gian |
| POST | `/api/notification` | Gửi thông báo |
| POST | `/api/clear` | Xóa thông báo |
| GET | `/api/status` | Lấy trạng thái |

### Ví dụ test bằng curl

```bash
# Gửi thời gian
curl -X POST http://192.168.1.100/api/time \
  -H "Content-Type: application/json" \
  -d '{"hour":23,"minute":7,"second":30,"day":18,"month":6,"year":2026,"weekday":4}'

# Gửi thông báo
curl -X POST http://192.168.1.100/api/notification \
  -H "Content-Type: application/json" \
  -d '{"title":"Zalo","message":"Ban co tin nhan moi","icon":"chat"}'

# Xóa thông báo
curl -X POST http://192.168.1.100/api/clear

# Kiểm tra trạng thái
curl http://192.168.1.100/api/status
```

## Cấu trúc code

```
main/
├── main.c              # Entry point, khởi tạo hệ thống
├── display_driver.c/h  # Driver SPI + ST7735
├── wifi_manager.c/h    # Quản lý WiFi STA
├── http_server.c/h     # HTTP server + JSON API
├── hud_renderer.c/h    # Vẽ giao diện HUD
├── font8x8.h           # Font bitmap 8x8
└── Kconfig.projbuild   # Menu cấu hình
```
