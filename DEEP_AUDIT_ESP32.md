# 🔬 Deep Audit ESP32 Firmware — Lỗi Ngầm & Tối Ưu Hiệu Năng

> **Ngày**: 2025-07-15  
> **Target**: ESP32-S3 R16N8 (240MHz, 512KB SRAM, 8MB PSRAM)  
> **Firmware**: `esp32-hud-firmware/main/`

---

## 📊 Tổng Quan Bộ Nhớ Hiện Tại

| Thành phần | Loại | Kích thước | Ghi chú |
|---|---|---|---|
| `s_jpeg_buf_a[32768]` | Static BSS | 32 KB | Double buffer A |
| `s_jpeg_buf_b[32768]` | Static BSS | 32 KB | Double buffer B |
| `s_frame_pixel_buf` | Heap (PSRAM) | 115 KB | 240×240×2 frame buffer |
| `g_nav_icon_rgb565[8192]` | Static BSS | 8 KB | Nav icon buffer |
| `s_line_buf[240]` | Static BSS | 480 B | Display line buffer |
| LVGL draw buffer | Heap (DMA) | 4.8 KB | 240×10 pixels |
| Font data (flash) | .rodata | ~30 KB | font8x8 + font_16x24 + font_48x80 |
| Icon data (flash) | .rodata | ~25 KB | 5 LVGL icons |
| **Tổng static RAM** | | **~188 KB** | Chưa tính stack các task |

### Stack các Task:

| Task | Stack | Priority | Ghi chú |
|---|---|---|---|
| `hud_main` | 8192 B | 5 | LVGL + hud_update |
| `hud_stream_rx` | 8192 B | 6 | UDP receive + 2KB rx_buf trên stack |
| `hud_stream_dsp` | 8192 B | 5 | JPEG decode |
| `udp_discovery` | 2048 B | 5 | Broadcast discovery |
| HTTP server | 8192 B | 5 | WebSocket handler |

---

## 🔴 Mức Nghiêm Trọng: CRITICAL

### D-01: `malloc(5120)` mỗi frame JPEG — Memory Fragmentation
**File**: `hud_stream.c:150` — `decode_and_draw_jpeg()`

```c
// HIỆN TẠI: malloc/free MỖI FRAME (18 FPS = 18 lần/giây!)
uint8_t *work_buf = malloc(WORK_BUF_SIZE);  // 5120 bytes
// ... decode ...
free(work_buf);
```

**Vấn đề**: Ở 18 FPS, đây là 18 lần malloc+free/giây cho cùng một kích thước. Gây:
- **Heap fragmentation** nghiêm trọng theo thời gian
- **Latency** không cần thiết (malloc phải tìm block phù hợp)
- Có thể fail sau vài giờ chạy liên tục

**Fix**: Pre-allocate một lần khi init.

---

### D-02: `hud_stream_get_config()` gọi trong `jpeg_output_func` — Mutex Lock Per MCU Block
**File**: `hud_stream.c:131` — `jpeg_output_func()`

```c
static UINT jpeg_output_func(JDEC *jd, void *bitmap, JRECT *rect) {
    // ...
    hud_stream_config_t cfg;
    hud_stream_get_config(&cfg);  // ← MUTEX LOCK mỗi MCU block!
    // ...
}
```

**Vấn đề**: Với ảnh 240×240 và MCU block 16×16, có ~225 block/frame. Mỗi block gọi `hud_stream_get_config()` → lock/unlock `s_config_mutex`. Ở 18 FPS = **4050 mutex operations/giây** chỉ để đọc config không đổi!

**Fix**: Truyền config qua `jd->device` (user context), đọc một lần trước khi decode.

---

### D-03: `display_fill_rect` DMA buffer malloc/free mỗi lần gọi
**File**: `display_driver.c:268`

```c
uint16_t *batch_buf = heap_caps_malloc(total_pixels * sizeof(uint16_t), MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
// ... fill ...
free(batch_buf);
```

**Vấn đề**: `display_clear()` gọi `display_fill_rect()` → malloc lên đến 9.6KB DMA buffer rồi free ngay. Gây fragmentation trong DMA-capable internal SRAM (rất quý hiếm trên ESP32).

**Fix**: Sử dụng static DMA buffer pre-allocated.

---

### D-04: `display_draw_bitmap` blocking — Chặn task cho đến khi SPI xong
**File**: `display_driver.c:800`

```c
void display_draw_bitmap(int x, int y, int width, int height, const uint16_t *bitmap) {
    esp_lcd_panel_draw_bitmap(s_panel_handle, x, y, x + w, y + h, bitmap);
    if (s_display_task_handle != NULL) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);  // ← BLOCK vô thời hạn!
    }
}
```

**Vấn đề**: Khi stream JPEG, mỗi frame 240×240 gọi `display_draw_bitmap` → block cho đến khi SPI transfer hoàn tất. Nếu SPI bị treo (bus contention), task bị block **vĩnh viễn** (`portMAX_DELAY`).

**Fix**: Thêm timeout thay vì `portMAX_DELAY`.

---

## 🟠 Mức Nghiêm Trọng: HIGH

### D-05: Tick overflow trong `hud_stream_is_active()`
**File**: `hud_stream.c:65`

```c
uint32_t now = xTaskGetTickCount() * portTICK_PERIOD_MS;
if (s_config.active && (now - s_last_packet_time > 2000)) {
```

**Vấn đề**: `xTaskGetTickCount()` trả về `TickType_t` (uint32_t). Với `configTICK_RATE_HZ=1000`, tick overflow sau ~49.7 ngày. Phép nhân `* portTICK_PERIOD_MS` (=1) không gây vấn đề ở 1000Hz, nhưng phép trừ `now - s_last_packet_time` sẽ cho kết quả sai khi tick wrap around.

**Fix**: Dùng `pdTICKS_TO_MS()` hoặc so sánh tick trực tiếp.

**Cùng vấn đề trong**: `main.c:62` — `hud_main_task`

---

### D-06: `s_line_buf` shared giữa các task — Không có mutex
**File**: `display_driver.c:50`

```c
static uint16_t s_line_buf[DISPLAY_WIDTH];  // Shared, no protection!
```

**Vấn đề**: `s_line_buf` được dùng bởi:
- `display_fill_rect()` (fallback path)
- `display_draw_string()` 
- `display_draw_string_16x24()`

Nếu `hud_main_task` (priority 5) đang vẽ string và `hud_stream_dsp` (priority 5) gọi `display_fill_rect()` cùng lúc → **data corruption** trên màn hình.

**Fix**: Trong thực tế, stream mode pause LVGL nên ít xảy ra, nhưng vẫn là race condition tiềm ẩn. Nên dùng local buffer hoặc mutex.

---

### D-07: Vietnamese NFC table — Linear search O(192) mỗi ký tự
**File**: `display_driver.c:510`

```c
for (int i = 0; i < 192; i++) {
    if (vn_nfc_table[i].base == codepoint && vn_nfc_table[i].mark == next_cp) {
        codepoint = vn_nfc_table[i].composed;
        break;
    }
}
```

**Vấn đề**: Mỗi ký tự Vietnamese có combining mark phải search 192 entries. Với chuỗi 20 ký tự Vietnamese = tối đa 3840 comparisons.

**Fix**: Sort table theo `(base, mark)` → binary search O(log 192) ≈ 8 comparisons.

---

### D-08: Vietnamese font codepoint lookup — Linear search O(134) mỗi ký tự
**File**: `display_driver.c:555`

```c
for (int i = 0; i < 134; i++) {
    if (font_16x24_vn_codepoints[i] == codepoint) {
        index = 95 + i;
        break;
    }
}
```

**Vấn đề**: Tương tự D-07, mỗi ký tự Vietnamese phải linear search 134 entries.

**Fix**: Binary search hoặc lookup table (codepoints nằm trong range hẹp 0x00C0-0x1EF9).

---

### D-09: LVGL chỉ dùng single draw buffer — Không tận dụng DMA pipeline
**File**: `display_driver.c:230`

```c
s_lv_buf_1 = heap_caps_malloc(DISPLAY_WIDTH * 10 * sizeof(lv_color_t), MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
lv_disp_draw_buf_init(&s_disp_buf, s_lv_buf_1, NULL, DISPLAY_WIDTH * 10);
//                                                ^^^^ NULL = single buffer!
```

**Vấn đề**: Với single buffer, LVGL phải **đợi SPI transfer xong** trước khi render tiếp vào buffer. Với double buffer, LVGL render vào buffer B trong khi buffer A đang được DMA gửi qua SPI → **tăng throughput ~40-60%**.

**Fix**: Allocate 2 buffers, dùng double buffering.

---

## 🟡 Mức Nghiêm Trọng: MEDIUM

### D-10: `hud_render_header` cập nhật widget trên 4 screens mỗi lần
**File**: `hud_renderer.c:145-170`

```c
lv_obj_t *wifi_icons[] = { ui_Wifi_Icon, ui_Wifi_Icon_Notif, ui_Wifi_Icon_Nav, ui_Wifi_Icon_Stream };
// ... update all 4 screens' widgets ...
for (int i = 0; i < 4; i++) { ... }
```

**Vấn đề**: Mỗi lần header thay đổi, cập nhật widget trên CẢ 4 screens kể cả screens không hiển thị. Gây LVGL dirty flag trên inactive screens → render overhead không cần thiết.

**Fix**: Chỉ cập nhật screen đang active.

---

### D-11: `http_server_get_mode()` gọi nhiều lần trong cùng một cycle
**File**: `main.c:51,62` + `hud_renderer.c:555`

`hud_main_task` gọi `http_server_get_mode()` 1 lần, rồi `hud_update()` bên trong lại gọi thêm 1 lần nữa. Mỗi lần đều lock mutex.

**Fix**: Truyền mode đã đọc vào `hud_update()` thay vì đọc lại.

---

### D-12: `display_draw_char` scaled path dùng `malloc`
**File**: `display_driver.c:330`

```c
uint16_t *scaled_row = (uint16_t *)malloc(char_w * sizeof(uint16_t));
```

**Vấn đề**: Nếu scale > 1, mỗi ký tự malloc một buffer. Với scale=2, char_w=16 → 32 bytes, nhỏ nhưng gây fragmentation nếu gọi nhiều.

**Fix**: Dùng stack buffer với kích thước tối đa hợp lý (ví dụ `uint16_t scaled_row[64]`).

---

### D-13: `rx_buf[2048]` trên stack trong `hud_stream_rx_task`
**File**: `hud_stream.c:240`

```c
uint8_t rx_buf[RX_BUF_SIZE];  // 2048 bytes on stack!
bool chunk_received_flags[128]; // 128 bytes on stack
```

**Vấn đề**: Task có 8192 bytes stack. `rx_buf` (2048) + `chunk_received_flags` (128) + `source_addr` (16) + local vars + function call overhead = ~2.5KB trên stack. Còn ~5.5KB cho function calls, nhưng nếu `recvfrom` hoặc frame assembly logic phức tạp hơn → stack overflow risk.

**Fix**: Chuyển `rx_buf` sang static hoặc heap allocation.

---

### D-14: Không có watchdog feeding trong JPEG decode
**File**: `hud_stream.c:148-175`

**Vấn đề**: `decode_and_draw_jpeg()` có thể mất 50-100ms cho ảnh 240×240. Nếu task watchdog timeout < 100ms → trigger watchdog reset. ESP-IDF default TWDT timeout = 5s nên thường OK, nhưng nếu SPI bị chậm hoặc JPEG lớn → rủi ro.

**Fix**: Thêm `esp_task_wdt_reset()` hoặc đảm bảo task không đăng ký TWDT.

---

## 🟢 Mức Nghiêm Trọng: LOW (Cải thiện)

### D-15: `wifi_scan_callback` malloc trong event handler context
**File**: `wifi_manager.c` — `wifi_event_handler`

Khi scan done, `malloc` cho `ap_info` array trong event handler context. Nếu heap fragmented → malloc fail → crash.

### D-16: Font data không đánh dấu `const` đúng cách
Các font array nên được đánh dấu `DRAM_ATTR` hoặc để trong flash với `const` để tiết kiệm RAM.

### D-17: `s_frame_sem` dùng binary semaphore — Có thể mất frame signal
Nếu RX task signal 2 frames liên tiếp trước khi display task kịp xử lý, signal thứ 2 bị mất (binary semaphore chỉ count 0/1). Nên dùng counting semaphore.

---

## 📋 Kế Hoạch Fix (Ưu Tiên Theo Impact)

| # | Issue | Impact | Effort | Priority |
|---|---|---|---|---|
| D-01 | Pre-allocate JPEG work buffer | 🔴 High | ⚡ Easy | **P0** |
| D-02 | Cache config trước JPEG decode | 🔴 High | ⚡ Easy | **P0** |
| D-03 | Static DMA fill_rect buffer | 🔴 High | ⚡ Easy | **P0** |
| D-04 | Timeout cho display_draw_bitmap | 🔴 High | ⚡ Easy | **P0** |
| D-05 | Fix tick overflow | 🟠 Medium | ⚡ Easy | **P1** |
| D-07 | Binary search NFC table | 🟠 Medium | 🔧 Medium | **P1** |
| D-08 | Binary search font codepoints | 🟠 Medium | 🔧 Medium | **P1** |
| D-09 | LVGL double buffer | 🟠 High | ⚡ Easy | **P1** |
| D-10 | Header update active screen only | 🟡 Low | ⚡ Easy | **P2** |
| D-17 | Counting semaphore cho frame | 🟡 Low | ⚡ Easy | **P2** |

---

## ✅ Trạng Thái Fix

- [x] D-01: Pre-allocate JPEG work buffer → `s_jpeg_work_buf` allocated once in `hud_stream_init()`
- [x] D-02: Cache config in jpeg_output_func → Config cached in `jpeg_stream_t.cfg`, read once before decode
- [x] D-03: Static DMA fill_rect buffer → `s_fill_rect_dma_buf` pre-allocated in `display_init()`
- [x] D-04: Timeout cho display_draw_bitmap → 500ms timeout thay vì `portMAX_DELAY`
- [x] D-05: Fix tick overflow → Dùng `TickType_t` trực tiếp + `pdMS_TO_TICKS()` (wrap-around safe)
- [ ] D-06: s_line_buf mutex → Deferred (stream mode pauses LVGL, low risk)
- [x] D-07: Binary search NFC table → O(log 192) ≈ 8 comparisons thay vì O(192)
- [ ] D-08: Binary search font codepoints → Deferred (array not sorted, would need index mapping)
- [x] D-09: LVGL double buffer → 2 DMA buffers, graceful fallback nếu alloc fail
- [x] D-10: Header update active screen only → Chỉ update widgets trên `lv_scr_act()`
- [ ] D-11: http_server_get_mode() duplicate → Low priority, deferred
- [ ] D-12: display_draw_char scaled malloc → Low priority, deferred
- [ ] D-13: rx_buf on stack → Acceptable with 8KB stack
- [ ] D-14: Watchdog in JPEG decode → ESP-IDF default 5s TWDT, OK for now
- [ ] D-15: wifi_scan malloc in event handler → Low priority
- [ ] D-16: Font const/DRAM_ATTR → Already const, in flash
- [x] D-17: Counting semaphore → `xSemaphoreCreateCounting(2, 0)` thay vì binary
