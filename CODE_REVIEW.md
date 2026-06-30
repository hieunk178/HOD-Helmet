# 🔍 CODE REVIEW — HUD Helmet Project

> **Ngày đánh giá:** 2026-06-30
> **Phạm vi:** ESP32 Firmware + Android App
> **Tổng lỗi:** 5 Critical · 11 Medium · 5 Low

---

## 📑 MỤC LỤC

- [🔍 CODE REVIEW — HUD Helmet Project](#-code-review--hud-helmet-project)
  - [📑 MỤC LỤC](#-mục-lục)
  - [PHẦN A: ESP32 FIRMWARE](#phần-a-esp32-firmware)
    - [F-01: Race condition double buffer trong `hud_stream.c`](#f-01-race-condition-double-buffer-trong-hud_streamc)
      - [Mô tả vấn đề](#mô-tả-vấn-đề)
      - [Code hiện tại (LỖI)](#code-hiện-tại-lỗi)
      - [Code sửa](#code-sửa)
      - [Cách test](#cách-test)
    - [F-02: Dangling WebSocket request pointer trong `http_server.c`](#f-02-dangling-websocket-request-pointer-trong-http_serverc)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-1)
      - [Code hiện tại (LỖI)](#code-hiện-tại-lỗi-1)
      - [Code sửa](#code-sửa-1)
      - [Cách test](#cách-test-1)
    - [F-03: LVGL thread-safety với SPI bus](#f-03-lvgl-thread-safety-với-spi-bus)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-2)
      - [Code sửa](#code-sửa-2)
    - [F-04: `display_fill_rect` vẽ từng dòng rất chậm](#f-04-display_fill_rect-vẽ-từng-dòng-rất-chậm)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-3)
      - [Code hiện tại (CHẬM)](#code-hiện-tại-chậm)
      - [Code sửa](#code-sửa-3)
    - [F-05: UDP discovery socket tạo/đóng liên tục](#f-05-udp-discovery-socket-tạođóng-liên-tục)
      - [Code hiện tại](#code-hiện-tại)
      - [Code sửa](#code-sửa-4)
    - [F-06: Hardcoded SoftAP password](#f-06-hardcoded-softap-password)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-4)
      - [Code sửa](#code-sửa-5)
    - [F-07: WebSocket handler thiếu JSON parse error log](#f-07-websocket-handler-thiếu-json-parse-error-log)
      - [Code hiện tại](#code-hiện-tại-1)
      - [Code sửa](#code-sửa-6)
    - [F-08: Duplicate include `cJSON.h`](#f-08-duplicate-include-cjsonh)
      - [Code hiện tại](#code-hiện-tại-2)
      - [Fix](#fix)
    - [F-09: Thiếu `display_draw_bitmap` declaration trong header](#f-09-thiếu-display_draw_bitmap-declaration-trong-header)
      - [Mô tả](#mô-tả)
      - [Fix](#fix-1)
    - [F-10: Boot step numbering không nhất quán](#f-10-boot-step-numbering-không-nhất-quán)
      - [Code hiện tại](#code-hiện-tại-3)
      - [Fix](#fix-2)
  - [PHẦN B: ANDROID APP](#phần-b-android-app)
    - [A-01: Static ViewModel reference gây memory leak](#a-01-static-viewmodel-reference-gây-memory-leak)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-5)
      - [Code sửa](#code-sửa-7)
    - [A-02: CoroutineScope không có SupervisorJob](#a-02-coroutinescope-không-có-supervisorjob)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-6)
      - [Code sửa](#code-sửa-8)
    - [A-03: WebSocketManager scope không bao giờ cancel](#a-03-websocketmanager-scope-không-bao-giờ-cancel)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-7)
      - [Code sửa](#code-sửa-9)
    - [A-04: Bitmap allocation mỗi frame trong ScreenCaptureService](#a-04-bitmap-allocation-mỗi-frame-trong-screencaptureservice)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-8)
      - [Code sửa](#code-sửa-10)
    - [A-05: Chỉ buffer 1 pending notification](#a-05-chỉ-buffer-1-pending-notification)
      - [Code hiện tại](#code-hiện-tại-4)
      - [Code sửa](#code-sửa-11)
    - [A-06: Weather API hardcode tọa độ Hà Nội](#a-06-weather-api-hardcode-tọa-độ-hà-nội)
      - [Code hiện tại](#code-hiện-tại-5)
      - [Code sửa](#code-sửa-12)
    - [A-07: OkHttpClient tạo mới mỗi lần gọi weather](#a-07-okhttpclient-tạo-mới-mỗi-lần-gọi-weather)
      - [Code hiện tại](#code-hiện-tại-6)
      - [Code sửa](#code-sửa-13)
    - [A-08: ByteArrayOutputStream tạo mới mỗi frame trong MapNavStreamService](#a-08-bytearrayoutputstream-tạo-mới-mỗi-frame-trong-mapnavstreamservice)
      - [Code hiện tại](#code-hiện-tại-7)
      - [Code sửa](#code-sửa-14)
    - [A-09: UDP discovery port conflict](#a-09-udp-discovery-port-conflict)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-9)
      - [Code sửa](#code-sửa-15)
    - [A-10: sendJson callback chạy trên IO thread](#a-10-sendjson-callback-chạy-trên-io-thread)
      - [Mô tả vấn đề](#mô-tả-vấn-đề-10)
      - [Code sửa](#code-sửa-16)
    - [A-11: Deprecated PreferenceManager API](#a-11-deprecated-preferencemanager-api)
      - [Code hiện tại](#code-hiện-tại-8)
      - [Code sửa](#code-sửa-17)
  - [PHẦN C: TÓM TẮT ƯU TIÊN FIX](#phần-c-tóm-tắt-ưu-tiên-fix)
    - [🔴 Ưu tiên 1 — Fix ngay (có thể gây crash/leak)](#-ưu-tiên-1--fix-ngay-có-thể-gây-crashleak)
    - [🟡 Ưu tiên 2 — Fix sớm (ảnh hưởng performance/UX)](#-ưu-tiên-2--fix-sớm-ảnh-hưởng-performanceux)
    - [🟢 Ưu tiên 3 — Fix khi rảnh (cosmetic/minor)](#-ưu-tiên-3--fix-khi-rảnh-cosmeticminor)
    - [⏱️ Tổng effort ước tính](#️-tổng-effort-ước-tính)

---

## PHẦN A: ESP32 FIRMWARE

---

### F-01: Race condition double buffer trong `hud_stream.c`

| | |
|---|---|
| **Mức độ** | 🔴 CRITICAL |
| **File** | `esp32-hud-firmware/main/hud_stream.c` |
| **Dòng** | ~10-18, ~170-190 |

#### Mô tả vấn đề

Sử dụng `volatile` cho double buffer swap giữa RX task và Display task. `volatile` chỉ đảm bảo compiler không optimize away read/write, **KHÔNG đảm bảo atomicity** cho multi-word operations (swap 2 pointers + 2 size variables).

Kịch bản lỗi:
1. RX task đang ghi chunk vào `s_write_buf`
2. Display task thấy `s_write_frame_complete == true`, bắt đầu swap
3. RX task ghi chunk tiếp vào buffer đã bị swap → **data corruption**

#### Code hiện tại (LỖI)

```c
// hud_stream.c dòng ~10-18
static uint8_t *volatile s_write_buf = s_jpeg_buf_a;
static uint8_t *volatile s_read_buf = s_jpeg_buf_b;
static volatile uint32_t s_write_size = 0;
static volatile uint32_t s_read_size = 0;
static volatile bool s_frame_ready = false;
static volatile bool s_write_frame_complete = false;

// hud_stream.c dòng ~170-190 (display task)
if (!s_frame_ready && s_write_frame_complete) {
    uint8_t *temp = s_write_buf;
    s_write_buf = s_read_buf;
    s_read_buf = temp;
    s_read_size = s_write_size;
    s_frame_ready = true;
    s_write_frame_complete = false;
}
```

#### Code sửa

```c
// ===== Thêm mutex mới =====
static SemaphoreHandle_t s_buf_mutex = NULL;

// ===== Trong hud_stream_init(), thêm: =====
if (s_buf_mutex == NULL) {
    s_buf_mutex = xSemaphoreCreateMutex();
}

// ===== Display task — swap buffer có bảo vệ =====
if (xSemaphoreTake(s_buf_mutex, pdMS_TO_TICKS(5)) == pdTRUE) {
    if (!s_frame_ready && s_write_frame_complete) {
        uint8_t *temp = s_write_buf;
        s_write_buf = s_read_buf;
        s_read_buf = temp;
        s_read_size = s_write_size;
        s_frame_ready = true;
        s_write_frame_complete = false;
    }
    xSemaphoreGive(s_buf_mutex);
}

// ===== RX task — khi hoàn thành frame, cũng cần bảo vệ =====
// Thay thế đoạn:
//   s_write_size = total_size;
//   s_write_frame_complete = true;
// Bằng:
if (xSemaphoreTake(s_buf_mutex, pdMS_TO_TICKS(5)) == pdTRUE) {
    s_write_size = total_size;
    s_write_frame_complete = true;
    xSemaphoreGive(s_buf_mutex);
}
xSemaphoreGive(s_frame_sem);
```

#### Cách test

- Stream video liên tục 5+ phút, quan sát xem có frame bị artifact/glitch không
- Dùng `ESP_LOGI` log frame_id ở cả RX và Display task, kiểm tra thứ tự

---

### F-02: Dangling WebSocket request pointer trong `http_server.c`

| | |
|---|---|
| **Mức độ** | 🔴 CRITICAL |
| **File** | `esp32-hud-firmware/main/http_server.c` |
| **Dòng** | ~30, ~55, ~65 |

#### Mô tả vấn đề

`httpd_req_t *req` chỉ hợp lệ trong scope của handler function. Sau khi `ws_handler()` return, ESP-IDF có thể reuse hoặc free bộ nhớ đó. Khi `wifi_scan_callback()` được gọi async (sau khi scan hoàn thành), nó dùng `s_last_ws_req` — lúc này pointer đã invalid → **undefined behavior / crash**.

#### Code hiện tại (LỖI)

```c
static httpd_req_t *s_last_ws_req = NULL;

// Trong ws_handler():
s_last_ws_req = req;

// Trong wifi_scan_callback():
static void wifi_scan_callback(void *results, int count) {
    if (!s_last_ws_req) return;
    // ... dùng s_last_ws_req để gửi frame → NGUY HIỂM
    send_ws_json(s_last_ws_req, root);
}
```

#### Code sửa

```c
// ===== Thay thế s_last_ws_req bằng socket fd + server handle =====
static httpd_handle_t s_server = NULL;  // đã có
static int s_ws_fd = -1;               // THÊM MỚI

// ===== Hàm gửi WS frame an toàn qua fd =====
static void send_ws_json_async(cJSON *root) {
    if (s_server == NULL || s_ws_fd < 0) return;

    char *str = cJSON_PrintUnformatted(root);
    if (str) {
        httpd_ws_frame_t ws_pkt;
        memset(&ws_pkt, 0, sizeof(httpd_ws_frame_t));
        ws_pkt.payload = (uint8_t*)str;
        ws_pkt.len = strlen(str);
        ws_pkt.type = HTTPD_WS_TYPE_TEXT;
        // Gửi async qua fd — an toàn ngay cả ngoài handler context
        httpd_ws_send_frame_async(s_server, s_ws_fd, &ws_pkt);
        free(str);
    }
}

// ===== Trong ws_handler(), lưu fd thay vì req =====
// Thay:  s_last_ws_req = req;
// Bằng:
s_ws_fd = httpd_req_to_sockfd(req);

// ===== Trong wifi_scan_callback(), dùng hàm mới =====
static void wifi_scan_callback(void *results, int count) {
    if (s_ws_fd < 0) return;
    wifi_ap_record_t *ap_info = (wifi_ap_record_t *)results;
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "cmd", "wifi_scan_result");
    cJSON *arr = cJSON_AddArrayToObject(root, "networks");
    for (int i = 0; i < count; i++) {
        cJSON *item = cJSON_CreateObject();
        cJSON_AddStringToObject(item, "ssid", (char *)ap_info[i].ssid);
        cJSON_AddNumberToObject(item, "rssi", ap_info[i].rssi);
        cJSON_AddItemToArray(arr, item);
    }
    send_ws_json_async(root);  // Dùng hàm async an toàn
    cJSON_Delete(root);
}

// ===== Tương tự cho send_saved_networks() =====
// Thay send_ws_json(req, root) → send_ws_json_async(root)
// Và bỏ parameter req khỏi function signature
```

#### Cách test

- Kết nối WebSocket từ Android app
- Gửi lệnh `wifi_scan`
- Đợi scan hoàn thành (2-5 giây)
- Kiểm tra ESP32 không crash và kết quả scan trả về đúng

---

### F-03: LVGL thread-safety với SPI bus

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `esp32-hud-firmware/main/hud_stream.c`, `main.c` |

#### Mô tả vấn đề

LVGL flush callback (`display_flush_cb`) và stream display (`display_draw_bitmap`) đều gửi data qua SPI bus. Nếu cả hai chạy đồng thời từ 2 task khác nhau, SPI transactions sẽ bị interleave → display corruption.

Hiện tại code đã có logic tạm dừng LVGL khi stream active (trong `hud_main_task`), nhưng có race window nhỏ giữa check `hud_stream_is_active()` và `lv_timer_handler()`.

#### Code sửa

```c
// ===== display_driver.h — thêm SPI mutex API =====
/**
 * @brief Acquire exclusive SPI bus access for display operations
 * @param timeout_ms Maximum wait time in milliseconds
 * @return true if mutex acquired, false if timeout
 */
bool display_acquire_spi(uint32_t timeout_ms);

/**
 * @brief Release SPI bus access
 */
void display_release_spi(void);

// ===== display_driver.c — implement =====
static SemaphoreHandle_t s_spi_mutex = NULL;

// Trong display_init(), sau khi init SPI bus:
s_spi_mutex = xSemaphoreCreateMutex();

bool display_acquire_spi(uint32_t timeout_ms) {
    if (s_spi_mutex == NULL) return true;
    return xSemaphoreTake(s_spi_mutex, pdMS_TO_TICKS(timeout_ms)) == pdTRUE;
}

void display_release_spi(void) {
    if (s_spi_mutex) xSemaphoreGive(s_spi_mutex);
}

// ===== main.c — wrap lv_timer_handler =====
if (display_acquire_spi(10)) {
    lv_timer_handler();
    display_release_spi();
}

// ===== hud_stream.c — wrap display_draw_bitmap =====
if (display_acquire_spi(20)) {
    display_draw_bitmap(cfg.drawX, cfg.drawY, cfg.outW, cfg.outH, s_frame_pixel_buf);
    display_release_spi();
}
```

---

### F-04: `display_fill_rect` vẽ từng dòng rất chậm

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `esp32-hud-firmware/main/display_driver.c` |
| **Dòng** | `display_fill_rect()` function |

#### Mô tả vấn đề

Mỗi dòng pixel gọi 1 SPI transaction riêng. Với `display_clear(COLOR_BLACK)` trên màn 160×80, đó là **80 SPI transactions** thay vì 1. Mỗi transaction có overhead ~10-20µs cho setup → lãng phí 800-1600µs.

#### Code hiện tại (CHẬM)

```c
void display_fill_rect(int x, int y, int width, int height, uint16_t color)
{
    // ...
    for (int i = 0; i < width && i < DISPLAY_WIDTH; i++) {
        s_line_buf[i] = color;
    }
    for (int row = y; row < y + height; row++) {
        esp_lcd_panel_draw_bitmap(s_panel_handle, x, row, x + width, row + 1, s_line_buf);
    }
}
```

#### Code sửa

```c
void display_fill_rect(int x, int y, int width, int height, uint16_t color)
{
    if (s_panel_handle == NULL) return;
    if (x < 0 || y < 0 || x >= DISPLAY_WIDTH || y >= DISPLAY_HEIGHT) return;

    if (x + width > DISPLAY_WIDTH) width = DISPLAY_WIDTH - x;
    if (y + height > DISPLAY_HEIGHT) height = DISPLAY_HEIGHT - y;

    /* Cố gắng allocate buffer cho toàn bộ rect */
    int total_pixels = width * height;

    if (total_pixels <= DISPLAY_WIDTH) {
        /* Nhỏ — dùng s_line_buf, vẽ từng dòng (giữ nguyên logic cũ) */
        for (int i = 0; i < width; i++) {
            s_line_buf[i] = color;
        }
        for (int row = y; row < y + height; row++) {
            esp_lcd_panel_draw_bitmap(s_panel_handle, x, row, x + width, row + 1, s_line_buf);
        }
    } else {
        /* Lớn — allocate DMA buffer, vẽ 1 lần */
        uint16_t *rect_buf = heap_caps_malloc(total_pixels * sizeof(uint16_t), MALLOC_CAP_DMA);
        if (rect_buf) {
            for (int i = 0; i < total_pixels; i++) {
                rect_buf[i] = color;
            }
            esp_lcd_panel_draw_bitmap(s_panel_handle, x, y, x + width, y + height, rect_buf);
            free(rect_buf);
        } else {
            /* Fallback: vẽ từng dòng nếu không đủ RAM */
            for (int i = 0; i < width; i++) {
                s_line_buf[i] = color;
            }
            for (int row = y; row < y + height; row++) {
                esp_lcd_panel_draw_bitmap(s_panel_handle, x, row, x + width, row + 1, s_line_buf);
            }
        }
    }
}
```

**Hiệu quả:** `display_clear()` giảm từ 80 SPI transactions → 1 transaction. Tốc độ tăng ~10-50x.

---

### F-05: UDP discovery socket tạo/đóng liên tục

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `esp32-hud-firmware/main/wifi_manager.c` |
| **Dòng** | `udp_discovery_task()` |

#### Code hiện tại

```c
static void udp_discovery_task(void *pvParameters) {
    while (1) {
        if (s_state == WIFI_STATE_CONNECTED_STA || s_state == WIFI_STATE_AP_MODE) {
            int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
            if (sock >= 0) {
                // ... send broadcast ...
                close(sock);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
```

#### Code sửa

```c
static void udp_discovery_task(void *pvParameters) {
    int sock = -1;

    while (1) {
        if (s_state == WIFI_STATE_CONNECTED_STA || s_state == WIFI_STATE_AP_MODE) {
            /* Tạo socket 1 lần, reuse */
            if (sock < 0) {
                sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
                if (sock >= 0) {
                    int broadcastEnable = 1;
                    setsockopt(sock, SOL_SOCKET, SO_BROADCAST,
                               &broadcastEnable, sizeof(broadcastEnable));
                }
            }

            if (sock >= 0) {
                struct sockaddr_in dest_addr;
                dest_addr.sin_family = AF_INET;
                dest_addr.sin_port = htons(8888);
                dest_addr.sin_addr.s_addr = inet_addr("255.255.255.255");

                char payload[64];
                snprintf(payload, sizeof(payload), "HUD_HELMET_IP:%s", s_ip_address);

                int ret = sendto(sock, payload, strlen(payload), 0,
                                 (struct sockaddr *)&dest_addr, sizeof(dest_addr));
                if (ret < 0) {
                    /* Socket lỗi — đóng và tạo lại lần sau */
                    close(sock);
                    sock = -1;
                }
            }
        } else {
            /* WiFi chưa kết nối — đóng socket nếu đang mở */
            if (sock >= 0) {
                close(sock);
                sock = -1;
            }
        }
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
```

---

### F-06: Hardcoded SoftAP password

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `esp32-hud-firmware/main/wifi_manager.c` |
| **Dòng** | `start_softap()` |

#### Mô tả vấn đề

```c
.password = "12345678",
```

Password `12345678` quá yếu. Bất kỳ ai trong phạm vi WiFi đều có thể kết nối và gửi lệnh tới HUD (hiển thị notification giả, thay đổi mode, v.v.).

#### Code sửa

```c
// ===== Kconfig.projbuild — thêm config option =====
config ESP_SOFTAP_PASSWORD
    string "SoftAP Password"
    default "HUD@Helmet2026"
    help
        Password for the fallback SoftAP mode.
        Must be at least 8 characters.

// ===== wifi_manager.c — dùng config =====
static void start_softap(void) {
    // ...
    wifi_config_t ap_config = {
        .ap = {
            .ssid = "HOD_Helmet",
            .ssid_len = strlen("HOD_Helmet"),
            .channel = 1,
            .password = CONFIG_ESP_SOFTAP_PASSWORD,  // Từ menuconfig
            .max_connection = 2,   // Giảm từ 4 → 2 (chỉ cần 1 phone)
            .authmode = WIFI_AUTH_WPA2_PSK,
            // ...
        },
    };
    // ...
}
```

---

### F-07: WebSocket handler thiếu JSON parse error log

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `esp32-hud-firmware/main/http_server.c` |
| **Dòng** | `ws_handler()`, khoảng dòng xử lý TEXT frame |

#### Code hiện tại

```c
cJSON *root = cJSON_Parse((const char *)ws_pkt.payload);
if (root) {
    // ... xử lý bình thường
}
cJSON_Delete(root);
```

Nếu `cJSON_Parse` return NULL (JSON không hợp lệ), không có log nào → khó debug.

#### Code sửa

```c
cJSON *root = cJSON_Parse((const char *)ws_pkt.payload);
if (root) {
    // ... xử lý bình thường
} else {
    ESP_LOGW(TAG, "Failed to parse WS JSON: %.64s", ws_pkt.payload);
}
cJSON_Delete(root);  // cJSON_Delete(NULL) là safe
```

---

### F-08: Duplicate include `cJSON.h`

| | |
|---|---|
| **Mức độ** | 🟢 LOW |
| **File** | `esp32-hud-firmware/main/http_server.c` |
| **Dòng** | 8, 12 |

#### Code hiện tại

```c
#include "cJSON.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "cJSON.h"       // ← DUPLICATE
```

#### Fix

Xóa dòng `#include "cJSON.h"` thứ 2.

---

### F-09: Thiếu `display_draw_bitmap` declaration trong header

| | |
|---|---|
| **Mức độ** | 🟢 LOW |
| **File** | `esp32-hud-firmware/main/display_driver.h` |

#### Mô tả

`hud_stream.c` gọi `display_draw_bitmap()` nhưng function này không được khai báo trong `display_driver.h`. Compiler có thể tạo implicit declaration → warning.

#### Fix

Thêm vào `display_driver.h`:

```c
/**
 * @brief Draw a bitmap (raw RGB565 pixel array) to the display
 *
 * @param x      Start X coordinate
 * @param y      Start Y coordinate
 * @param width  Bitmap width in pixels
 * @param height Bitmap height in pixels
 * @param data   Pointer to RGB565 pixel data
 */
void display_draw_bitmap(int x, int y, int width, int height, const uint16_t *data);
```

---

### F-10: Boot step numbering không nhất quán

| | |
|---|---|
| **Mức độ** | 🟢 LOW |
| **File** | `esp32-hud-firmware/main/main.c` |
| **Dòng** | ~120-150 |

#### Code hiện tại

```c
ESP_LOGI(TAG, "[1/5] NVS Flash initialized");
ESP_LOGI(TAG, "[2/5] Display initialized");
ESP_LOGI(TAG, "[3/5] Boot screen displayed");
ESP_LOGI(TAG, "[4/5] Starting Smart WiFi Manager...");
ESP_LOGI(TAG, "[5/5] HTTP server started");
// ...
ESP_LOGI(TAG, "[6/6] Screen streaming initialization complete");  // ← Sai
```

#### Fix

Đổi tất cả thành `/6`:

```c
ESP_LOGI(TAG, "[1/6] NVS Flash initialized");
ESP_LOGI(TAG, "[2/6] Display initialized");
ESP_LOGI(TAG, "[3/6] Boot screen displayed");
ESP_LOGI(TAG, "[4/6] Starting Smart WiFi Manager...");
ESP_LOGI(TAG, "[5/6] HTTP server started");
ESP_LOGI(TAG, "[6/6] Screen streaming initialization complete");
```

---

## PHẦN B: ANDROID APP

---

### A-01: Static ViewModel reference gây memory leak

| | |
|---|---|
| **Mức độ** | 🔴 CRITICAL |
| **File** | `android-hud-controller/app/src/main/java/com/hudhelmet/controller/viewmodel/HudViewModel.kt` |

#### Mô tả vấn đề

```kotlin
companion object {
    var activeViewModel: HudViewModel? = null
}
init {
    activeViewModel = this
}
```

`HudViewModel` extends `AndroidViewModel` → giữ reference tới `Application`. Static reference `activeViewModel` giữ ViewModel sống mãi ngay cả khi Activity bị destroy. Hơn nữa, `MapNavStreamService` và `HudNotificationListenerService` truy cập trực tiếp `HudViewModel.activeViewModel` — vi phạm separation of concerns.

#### Code sửa

**Bước 1: Tạo shared event bus**

```kotlin
// File mới: model/HudEventBus.kt
package com.hudhelmet.controller.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Centralized event bus for Service ↔ ViewModel communication.
 * Replaces static ViewModel reference.
 */
object HudEventBus {
    // Events FROM services TO ViewModel
    sealed class ServiceEvent {
        data class NotificationSent(val title: String, val message: String, val success: Boolean) : ServiceEvent()
        data class NavigationUpdate(
            val currentLocation: RoutePoint?,
            val speed: Float,
            val bearing: Float,
            val navUpdate: Any? // NavigationEngine.NavigationUpdate
        ) : ServiceEvent()
        data class RouteUpdated(val route: RouteData) : ServiceEvent()
        object Arrived : ServiceEvent()
    }

    private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ServiceEvent> = _events.asSharedFlow()

    fun emit(event: ServiceEvent) {
        _events.tryEmit(event)
    }
}
```

**Bước 2: Services gửi event thay vì truy cập ViewModel trực tiếp**

```kotlin
// Trong HudNotificationListenerService, thay:
//   val activeVm = HudViewModel.activeViewModel
//   activeVm?.addSentNotification(...)
// Bằng:
HudEventBus.emit(HudEventBus.ServiceEvent.NotificationSent(notifTitle, notifMessage, true))

// Trong MapNavStreamService, thay:
//   val vm = HudViewModel.activeViewModel
//   vm?.updateNavigationStateFromService(...)
// Bằng:
HudEventBus.emit(HudEventBus.ServiceEvent.NavigationUpdate(point, speed, bearing, update))
```

**Bước 3: ViewModel lắng nghe events**

```kotlin
// Trong HudViewModel init {}:
viewModelScope.launch {
    HudEventBus.events.collect { event ->
        when (event) {
            is HudEventBus.ServiceEvent.NotificationSent -> {
                addSentNotification(event.title, event.message, event.success)
            }
            is HudEventBus.ServiceEvent.NavigationUpdate -> {
                // update nav state
            }
            is HudEventBus.ServiceEvent.RouteUpdated -> {
                _mapRoute.value = event.route
            }
            is HudEventBus.ServiceEvent.Arrived -> {
                handleArrival()
            }
        }
    }
}

// XÓA companion object { var activeViewModel }
// XÓA init { activeViewModel = this }
```

---

### A-02: CoroutineScope không có SupervisorJob

| | |
|---|---|
| **Mức độ** | 🔴 CRITICAL |
| **File** | `HudNotificationListenerService.kt` dòng ~42 |

#### Mô tả vấn đề

```kotlin
private val scope = CoroutineScope(Dispatchers.IO)
```

Không có `SupervisorJob`. Nếu bất kỳ child coroutine nào throw exception (ví dụ network error trong auto-discovery), **toàn bộ scope bị cancel** → service ngừng hoạt động hoàn toàn, không forward notification nữa.

#### Code sửa

```kotlin
// HudNotificationListenerService.kt
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

override fun onDestroy() {
    super.onDestroy()
    scope.cancel()  // THÊM: Clean cancel khi service bị destroy
    autoDiscoveryJob?.cancel()
    instance = null
}
```

Tương tự cho `MapNavStreamService.kt` — đã có `SupervisorJob()` trong scope, OK.

---

### A-03: WebSocketManager scope không bao giờ cancel

| | |
|---|---|
| **Mức độ** | 🔴 CRITICAL |
| **File** | `network/WebSocketManager.kt` dòng ~35 |

#### Mô tả vấn đề

```kotlin
private val scope = CoroutineScope(Dispatchers.IO)
```

`WebSocketManager` là singleton — scope này sống mãi mãi. Tất cả coroutines (reconnect, send) không bao giờ bị cancel ngay cả khi app process bị kill bởi OS.

#### Code sửa

```kotlin
class WebSocketManager private constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thêm method để clean up khi cần
    fun shutdown() {
        disconnect()
        scope.cancel()
    }
}
```

> **Lưu ý:** Vì WebSocketManager là singleton dùng chung giữa Activity và Service, việc cancel scope cần cẩn thận. Chỉ gọi `shutdown()` khi chắc chắn không còn component nào cần WebSocket.

---

### A-04: Bitmap allocation mỗi frame trong ScreenCaptureService

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `service/ScreenCaptureService.kt` |
| **Dòng** | Trong `startStreaming()` lambda |

#### Mô tả vấn đề

Mỗi frame tạo 3 Bitmap objects:
```kotlin
val bmp = Bitmap.createBitmap(bufferWidth, rawHeight, Bitmap.Config.ARGB_8888)
val cropped = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
```

Ở 18 FPS → **54 Bitmap allocations/giây** → GC pressure cực lớn → frame drop.

#### Code sửa

```kotlin
// Pre-allocate bitmaps TRƯỚC vòng loop
val reusableBmp = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
val reusableScaled = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
val scaleCanvas = Canvas(reusableScaled)
val scaleMatrix = Matrix()
val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
val baos = ByteArrayOutputStream(outW * outH)  // Pre-allocate

while (isActive) {
    val img = reader.acquireLatestImage()
    if (img != null) {
        try {
            val plane = img.planes[0]
            val buffer = plane.buffer

            // Reuse bitmap — copyPixelsFromBuffer overwrites existing data
            reusableBmp.copyPixelsFromBuffer(buffer)
            buffer.rewind()  // Reset buffer position cho frame tiếp

            // Scale + crop trực tiếp vào reusableScaled
            scaleCanvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
            val srcRect = Rect(cx, cy, cx + cw, cy + ch)
            val dstRect = Rect(0, 0, outW, outH)
            scaleCanvas.drawBitmap(reusableBmp, srcRect, dstRect, scalePaint)

            // Compress — reuse ByteArrayOutputStream
            baos.reset()
            reusableScaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val jpegBytes = baos.toByteArray()

            sendFrameChunks(socket, espAddress, streamId, frameId, jpegBytes)
            frameId = ((frameId + 1) % 65536).toShort()
        } finally {
            img.close()
        }
    }
    // ...
}

// Clean up khi loop kết thúc
reusableBmp.recycle()
reusableScaled.recycle()
```

**Hiệu quả:** Giảm từ 54 allocations/s → 0 allocations/s (chỉ `toByteArray()` tạo copy nhỏ). FPS ổn định hơn đáng kể.

---

### A-05: Chỉ buffer 1 pending notification

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `service/HudNotificationListenerService.kt` |
| **Dòng** | ~43 |

#### Code hiện tại

```kotlin
private var pendingNotification: NotificationData? = null
```

Nếu 3 notification đến khi offline, chỉ notification cuối được giữ.

#### Code sửa

```kotlin
import java.util.concurrent.ConcurrentLinkedQueue

private val pendingNotifications = ConcurrentLinkedQueue<NotificationData>()
private val MAX_PENDING = 5

// Khi nhận notification mà offline:
if (pendingNotifications.size >= MAX_PENDING) {
    pendingNotifications.poll()  // Bỏ cái cũ nhất
}
pendingNotifications.offer(notificationData)

// Khi kết nối lại (trong connectionState collector):
if (state == ConnectionState.CONNECTED) {
    // Gửi tất cả pending notifications
    while (pendingNotifications.isNotEmpty()) {
        val notif = pendingNotifications.poll() ?: break
        webSocketManager.sendNotification(notif) { success ->
            if (!success) {
                // Đưa lại vào queue nếu gửi thất bại
                pendingNotifications.offer(notif)
            }
        }
        delay(200)  // Tránh flood ESP32
    }
}
```

---

### A-06: Weather API hardcode tọa độ Hà Nội

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `viewmodel/HudViewModel.kt` |
| **Dòng** | `updateWeather()` |

#### Code hiện tại

```kotlin
val lat = 21.0285 // Fallback to Hanoi
val lon = 105.8542
```

#### Code sửa

```kotlin
private fun updateWeather() {
    lastWeatherAttemptTime = System.currentTimeMillis()
    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Ưu tiên dùng GPS location nếu có
            val loc = _currentLocation.value
            val lat = loc?.lat ?: 21.0285   // Fallback Hà Nội
            val lon = loc?.lon ?: 105.8542

            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m"
            // ... phần còn lại giữ nguyên
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
```

---

### A-07: OkHttpClient tạo mới mỗi lần gọi weather

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `viewmodel/HudViewModel.kt` |
| **Dòng** | `updateWeather()` |

#### Code hiện tại

```kotlin
private fun updateWeather() {
    // ...
    val client = okhttp3.OkHttpClient()  // Tạo mới mỗi lần!
    // ...
}
```

#### Code sửa

```kotlin
class HudViewModel(application: Application) : AndroidViewModel(application) {
    // Reuse OkHttpClient — chia sẻ connection pool
    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun updateWeather() {
        // ...
        // Thay: val client = okhttp3.OkHttpClient()
        // Bằng: dùng this.httpClient
        httpClient.newCall(request).execute().use { response ->
            // ...
        }
    }
}
```

---

### A-08: ByteArrayOutputStream tạo mới mỗi frame trong MapNavStreamService

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `service/MapNavStreamService.kt` |
| **Dòng** | Trong `startMapStreaming()` loop |

#### Code hiện tại

```kotlin
while (isActive && isRunning) {
    // ...
    val baos = ByteArrayOutputStream()  // Tạo mới mỗi frame!
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    // ...
}
```

#### Code sửa

```kotlin
// Tạo 1 lần TRƯỚC loop, reuse bằng reset()
val baos = ByteArrayOutputStream(240 * 240)  // Pre-size

while (isActive && isRunning) {
    // ...
    baos.reset()  // Clear data cũ, giữ buffer
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    val jpegBytes = baos.toByteArray()
    // ...
}
```

---

### A-09: UDP discovery port conflict

| | |
|---|---|
| **Mức độ** | 🟡 MEDIUM |
| **File** | `network/UdpDiscoveryManager.kt` |

#### Mô tả vấn đề

Cả `HudViewModel.startAutoDiscovery()` và `HudNotificationListenerService.startAutoDiscovery()` đều gọi `UdpDiscoveryManager.discoverEspIp()` → cả hai tạo `DatagramSocket(8888)` → `BindException` nếu chạy đồng thời.

#### Code sửa

```kotlin
object UdpDiscoveryManager {
    private const val TAG = "UdpDiscovery"
    private const val PORT = 8888
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun discoverEspIp(timeoutMs: Int = 5000): String? = withContext(Dispatchers.IO) {
        // Chỉ cho phép 1 discovery chạy tại 1 thời điểm
        if (!mutex.tryLock()) {
            Log.d(TAG, "Discovery already in progress, skipping")
            return@withContext null
        }

        try {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null)  // Unbound socket
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(PORT))
                socket.soTimeout = timeoutMs

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val message = String(packet.data, 0, packet.length).trim()
                if (message.startsWith("HUD_HELMET_IP:")) {
                    return@withContext message.substringAfter("HUD_HELMET_IP:")
                }
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "Discovery timeout")
            } catch (e: Exception) {
                Log.e(TAG, "Error during UDP discovery", e)
            } finally {
                socket?.close()
            }
            return@withContext null
        } finally {
            mutex.unlock()
        }
    }
}
```

---

### A-10: sendJson callback chạy trên IO thread

| | |
|---|---|
| **Mức độ** | 🟢 LOW |
| **File** | `network/WebSocketManager.kt` |
| **Dòng** | `sendJson()` |

#### Mô tả vấn đề

```kotlin
private fun sendJson(cmd: String, data: Any, callback: ((Boolean) -> Unit)? = null) {
    scope.launch {  // Dispatchers.IO
        // ...
        callback?.invoke(success)  // Callback chạy trên IO thread!
    }
}
```

Caller (ViewModel) update `MutableStateFlow` từ callback — StateFlow thread-safe nên không crash, nhưng nếu caller update UI trực tiếp thì sẽ lỗi.

#### Code sửa

```kotlin
private fun sendJson(cmd: String, data: Any, callback: ((Boolean) -> Unit)? = null) {
    scope.launch {
        try {
            val jsonElement = gson.toJsonTree(data).asJsonObject
            jsonElement.addProperty("cmd", cmd)
            val text = jsonElement.toString()
            val success = webSocket?.send(text) ?: false
            // Invoke callback trên Main thread
            if (callback != null) {
                withContext(Dispatchers.Main) {
                    callback.invoke(success)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending JSON", e)
            if (callback != null) {
                withContext(Dispatchers.Main) {
                    callback.invoke(false)
                }
            }
        }
    }
}
```

---

### A-11: Deprecated PreferenceManager API

| | |
|---|---|
| **Mức độ** | 🟢 LOW |
| **File** | `MainActivity.kt` |
| **Dòng** | `onCreate()` |

#### Code hiện tại

```kotlin
android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
```

#### Code sửa

```kotlin
// build.gradle.kts — thêm dependency (nếu chưa có):
implementation("androidx.preference:preference-ktx:1.2.1")

// MainActivity.kt:
import androidx.preference.PreferenceManager
// ...
PreferenceManager.getDefaultSharedPreferences(applicationContext)
```

---

## PHẦN C: TÓM TẮT ƯU TIÊN FIX

### 🔴 Ưu tiên 1 — Fix ngay (có thể gây crash/leak)

| ID | Vấn đề | File | Effort |
|----|--------|------|--------|
| F-01 | Race condition double buffer | `hud_stream.c` | 30 phút |
| F-02 | Dangling WS request pointer | `http_server.c` | 45 phút |
| A-01 | Static ViewModel reference | `HudViewModel.kt` + Services | 2 giờ |
| A-02 | CoroutineScope thiếu SupervisorJob | `HudNotificationListenerService.kt` | 5 phút |
| A-03 | WebSocketManager scope leak | `WebSocketManager.kt` | 10 phút |

### 🟡 Ưu tiên 2 — Fix sớm (ảnh hưởng performance/UX)

| ID | Vấn đề | File | Effort |
|----|--------|------|--------|
| F-04 | display_fill_rect chậm | `display_driver.c` | 30 phút |
| A-04 | Bitmap alloc mỗi frame | `ScreenCaptureService.kt` | 1 giờ |
| F-03 | LVGL SPI thread-safety | `display_driver.c` + `hud_stream.c` | 1 giờ |
| A-06 | Weather hardcode Hà Nội | `HudViewModel.kt` | 10 phút |
| A-07 | OkHttpClient tạo mới | `HudViewModel.kt` | 5 phút |
| A-08 | ByteArrayOutputStream mỗi frame | `MapNavStreamService.kt` | 5 phút |
| F-05 | UDP socket tạo/đóng liên tục | `wifi_manager.c` | 15 phút |
| F-06 | Hardcoded SoftAP password | `wifi_manager.c` + `Kconfig` | 15 phút |
| A-05 | Buffer 1 notification | `HudNotificationListenerService.kt` | 20 phút |
| F-07 | Thiếu JSON error log | `http_server.c` | 5 phút |
| A-09 | UDP port conflict | `UdpDiscoveryManager.kt` | 15 phút |

### 🟢 Ưu tiên 3 — Fix khi rảnh (cosmetic/minor)

| ID | Vấn đề | File | Effort |
|----|--------|------|--------|
| F-08 | Duplicate include | `http_server.c` | 1 phút |
| F-09 | Thiếu header declaration | `display_driver.h` | 2 phút |
| F-10 | Boot step numbering | `main.c` | 2 phút |
| A-10 | Callback trên IO thread | `WebSocketManager.kt` | 10 phút |
| A-11 | Deprecated API | `MainActivity.kt` | 5 phút |

---

### ⏱️ Tổng effort ước tính

| Mức độ | Số lượng | Effort |
|--------|----------|--------|
| 🔴 Critical | 5 | ~3.5 giờ |
| 🟡 Medium | 11 | ~3.5 giờ |
| 🟢 Low | 5 | ~20 phút |
| **Tổng** | **21** | **~7.5 giờ** |

---

> **Ghi chú:** Sau khi fix xong mỗi nhóm, nên test lại toàn bộ flow:
> 1. Boot ESP32 → kết nối WiFi → hiển thị boot screen
> 2. Android app kết nối WebSocket → sync time → hiển thị đồng hồ
> 3. Gửi notification → hiển thị trên HUD
> 4. Stream screen → kiểm tra FPS ổn định
> 5. Map navigation → kiểm tra turn-by-turn + minimap stream
> 6. Ngắt WiFi → reconnect → kiểm tra auto-recovery
