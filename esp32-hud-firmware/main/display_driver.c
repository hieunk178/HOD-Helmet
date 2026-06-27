/*
 * display_driver.c - TFT Display Driver Implementation
 *
 * Uses ESP-IDF esp_lcd API for modern, efficient SPI communication.
 * Supports both:
 *   - ST7735S 0.96" 160x80 (real hardware)
 *   - ILI9341 2.4" 240x320 (Wokwi simulator)
 *
 * Select display type via: idf.py menuconfig -> HUD Helmet Configuration
 */

#include "display_driver.h"
#include "font8x8.h"
#include "font_16x24.h"
#include "font_48x80.h"
#include "vn_nfc_table.h"

#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_panel_ops.h"
#include "esp_log.h"
#include "esp_heap_caps.h"

#ifdef CONFIG_HUD_DISPLAY_ILI9341
#include "esp_lcd_ili9341.h"
#elif defined(CONFIG_HUD_DISPLAY_ST7735)
#include "esp_lcd_st7735.h"
#endif

#include <string.h>
#include <stdlib.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "display";

#include "lvgl.h"
#include "esp_timer.h"

/* Display panel handle */
static esp_lcd_panel_handle_t s_panel_handle = NULL;
static esp_lcd_panel_io_handle_t s_io_handle = NULL;

/* Framebuffer line for batch drawing */
static uint16_t s_line_buf[DISPLAY_WIDTH];

/* LVGL display buffers and handles */
static lv_disp_draw_buf_t s_disp_buf;
static lv_color_t *s_lv_buf_1 = NULL;
static lv_disp_drv_t s_disp_drv;
static volatile bool s_lvgl_initialized = false;

/* Screen Stream Synchronization state */
static volatile bool s_lvgl_flushing = false;
TaskHandle_t s_display_task_handle = NULL;

static void lvgl_tick_cb(void *arg)
{
    lv_tick_inc(2); /* 2 ms */
}

static IRAM_ATTR bool notify_lvgl_flush_ready(esp_lcd_panel_io_handle_t panel_io, esp_lcd_panel_io_event_data_t *edata, void *user_ctx)
{
    if (s_lvgl_initialized && s_lvgl_flushing) {
        s_lvgl_flushing = false;
        lv_disp_drv_t *disp_driver = (lv_disp_drv_t *)user_ctx;
        lv_disp_flush_ready(disp_driver);
    } else if (s_display_task_handle != NULL) {
        BaseType_t xHigherPriorityTaskWoken = pdFALSE;
        vTaskNotifyGiveFromISR(s_display_task_handle, &xHigherPriorityTaskWoken);
        portYIELD_FROM_ISR(xHigherPriorityTaskWoken);
    }
    return false;
}

static void display_flush_cb(lv_disp_drv_t *disp_drv, const lv_area_t *area, lv_color_t *color_p)
{
    if (s_panel_handle != NULL) {
        s_lvgl_flushing = true;
        esp_lcd_panel_draw_bitmap(s_panel_handle, area->x1, area->y1, area->x2 + 1, area->y2 + 1, (uint16_t *)color_p);
    } else {
        lv_disp_flush_ready(disp_drv);
    }
}

esp_err_t display_init(void)
{
    esp_err_t ret;

#ifdef CONFIG_HUD_DISPLAY_ILI9341
    ESP_LOGI(TAG, "Initializing TFT display (ILI9341 240x320 - Wokwi Simulator)");
#elif defined(CONFIG_HUD_DISPLAY_ST7789)
    ESP_LOGI(TAG, "Initializing TFT display (ST7789 240x240 - Real Hardware)");
#else
    ESP_LOGI(TAG, "Initializing TFT display (ST7735 160x80 - Real Hardware)");
#endif

#ifdef CONFIG_HUD_HAS_BACKLIGHT_PIN
    /* Configure backlight GPIO (real hardware only) */
    gpio_config_t bk_gpio_config = {
        .mode = GPIO_MODE_OUTPUT,
        .pin_bit_mask = 1ULL << PIN_NUM_BLK,
    };
    gpio_config(&bk_gpio_config);
    gpio_set_level(PIN_NUM_BLK, 1);  /* Turn on backlight */
#endif

    /* Initialize SPI bus */
    spi_bus_config_t bus_config = {
        .sclk_io_num = PIN_NUM_SCLK,
        .mosi_io_num = PIN_NUM_MOSI,
        .miso_io_num = -1,          /* Not used for display */
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 32768,
    };
    ret = spi_bus_initialize(SPI2_HOST, &bus_config, SPI_DMA_CH_AUTO);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "SPI bus init failed: %s", esp_err_to_name(ret));
        return ret;
    }

    /* Configure LCD panel IO (SPI) */
    esp_lcd_panel_io_spi_config_t io_config = {
        .dc_gpio_num = PIN_NUM_DC,
        .cs_gpio_num = PIN_NUM_CS,      /* -1 = không có CS pin */
        .pclk_hz = 40 * 1000 * 1000,   /* 40 MHz - high speed for screen stream */
        .lcd_cmd_bits = 8,
        .lcd_param_bits = 8,
        .spi_mode = 3,                  /* ST7789 dùng SPI MODE3 (CPOL=1, CPHA=1) */
        .trans_queue_depth = 10,
        .on_color_trans_done = notify_lvgl_flush_ready,
        .user_ctx = &s_disp_drv,
        .flags = {
            .dc_low_on_data = 0,
        },
    };
    ret = esp_lcd_new_panel_io_spi(SPI2_HOST, &io_config, &s_io_handle);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Panel IO init failed: %s", esp_err_to_name(ret));
        return ret;
    }

    /* Install panel driver - select based on Kconfig */
    esp_lcd_panel_dev_config_t panel_config = {
        .reset_gpio_num = PIN_NUM_RST,
#if defined(CONFIG_HUD_DISPLAY_ILI9341) || defined(CONFIG_HUD_DISPLAY_ST7789)
        .rgb_ele_order = LCD_RGB_ELEMENT_ORDER_BGR,
#else
        .rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB,  /* ST7735 dùng RGB order */
#endif
        .bits_per_pixel = 16,
    };

#ifdef CONFIG_HUD_DISPLAY_ILI9341
    /* ILI9341 for Wokwi simulator */
    ret = esp_lcd_new_panel_ili9341(s_io_handle, &panel_config, &s_panel_handle);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Panel driver init failed: %s", esp_err_to_name(ret));
        return ret;
    }

    /* Reset and initialize */
    esp_lcd_panel_reset(s_panel_handle);
    esp_lcd_panel_init(s_panel_handle);
    esp_lcd_panel_invert_color(s_panel_handle, false);
    esp_lcd_panel_swap_xy(s_panel_handle, true);
    esp_lcd_panel_mirror(s_panel_handle, false, false);

#elif defined(CONFIG_HUD_DISPLAY_ST7735)
    /* ST7735 for real hardware */
    ret = esp_lcd_new_panel_st7735(s_io_handle, &panel_config, &s_panel_handle);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Panel driver init failed: %s", esp_err_to_name(ret));
        return ret;
    }

    /* Reset and initialize */
    esp_lcd_panel_reset(s_panel_handle);
    esp_lcd_panel_init(s_panel_handle);

    /* Configure for 160x80 display with proper offsets */
    esp_lcd_panel_invert_color(s_panel_handle, true);
    esp_lcd_panel_set_gap(s_panel_handle, 1, 26);   /* Offset for 160x80 variant */
    esp_lcd_panel_swap_xy(s_panel_handle, false);
    esp_lcd_panel_mirror(s_panel_handle, false, false);
#elif defined(CONFIG_HUD_DISPLAY_ST7789)
    /* ST7789 for real hardware (1.3" 240x240) */
    ret = esp_lcd_new_panel_st7789(s_io_handle, &panel_config, &s_panel_handle);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Panel driver init failed: %s", esp_err_to_name(ret));
        return ret;
    }

    /* Reset and initialize */
    esp_lcd_panel_reset(s_panel_handle);
    esp_lcd_panel_init(s_panel_handle);

    /* Configure for 240x240 display */
    esp_lcd_panel_invert_color(s_panel_handle, true);
    esp_lcd_panel_swap_xy(s_panel_handle, false);
    esp_lcd_panel_mirror(s_panel_handle, false, false);
#endif

    /* Turn on the display */
    esp_lcd_panel_disp_on_off(s_panel_handle, true);

    /* Clear screen to black */
    display_clear(COLOR_BLACK);

    ESP_LOGI(TAG, "Display initialized successfully (%dx%d)", DISPLAY_WIDTH, DISPLAY_HEIGHT);

    /* Initialize LVGL */
    lv_init();

    /* Allocate buffer for LVGL in DMA-capable internal SRAM */
    s_lv_buf_1 = heap_caps_malloc(DISPLAY_WIDTH * 10 * sizeof(lv_color_t), MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    if (s_lv_buf_1 == NULL) {
        ESP_LOGE(TAG, "Failed to allocate LVGL draw buffer!");
        return ESP_ERR_NO_MEM;
    }

    lv_disp_draw_buf_init(&s_disp_buf, s_lv_buf_1, NULL, DISPLAY_WIDTH * 10);

    lv_disp_drv_init(&s_disp_drv);
    s_disp_drv.hor_res = DISPLAY_WIDTH;
    s_disp_drv.ver_res = DISPLAY_HEIGHT;
    s_disp_drv.flush_cb = display_flush_cb;
    s_disp_drv.draw_buf = &s_disp_buf;
    lv_disp_drv_register(&s_disp_drv);
    s_lvgl_initialized = true;

    /* Setup LVGL tick timer (every 2ms) */
    const esp_timer_create_args_t tick_timer_args = {
        .callback = &lvgl_tick_cb,
        .name = "lvgl_tick"
    };
    esp_timer_handle_t tick_timer;
    esp_err_t err = esp_timer_create(&tick_timer_args, &tick_timer);
    if (err == ESP_OK) {
        esp_timer_start_periodic(tick_timer, 2000); /* 2ms period in microseconds */
    } else {
        ESP_LOGE(TAG, "Failed to create LVGL tick timer: %s", esp_err_to_name(err));
    }

    ESP_LOGI(TAG, "LVGL initialized successfully");
    return ESP_OK;
}


void display_clear(uint16_t color)
{
    display_fill_rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, color);
}

void display_fill_rect(int x, int y, int width, int height, uint16_t color)
{
    if (s_panel_handle == NULL) return;
    if (x < 0 || y < 0 || x >= DISPLAY_WIDTH || y >= DISPLAY_HEIGHT) return;

    /* Clamp dimensions */
    if (x + width > DISPLAY_WIDTH) width = DISPLAY_WIDTH - x;
    if (y + height > DISPLAY_HEIGHT) height = DISPLAY_HEIGHT - y;

    /* Fill line buffer with color (no manual swap - esp_lcd handles byte order) */
    for (int i = 0; i < width && i < DISPLAY_WIDTH; i++) {
        s_line_buf[i] = color;
    }

    /* Draw line by line */
    for (int row = y; row < y + height; row++) {
        esp_lcd_panel_draw_bitmap(s_panel_handle, x, row, x + width, row + 1, s_line_buf);
    }
}

void display_draw_pixel(int x, int y, uint16_t color)
{
    if (s_panel_handle == NULL) return;
    if (x < 0 || y < 0 || x >= DISPLAY_WIDTH || y >= DISPLAY_HEIGHT) return;

    esp_lcd_panel_draw_bitmap(s_panel_handle, x, y, x + 1, y + 1, &color);
}

void display_draw_char(int x, int y, char c, uint16_t color, uint16_t bg, int scale)
{
    if (s_panel_handle == NULL) return;
    if (c < 32 || c > 126) c = '?';  /* Replace unsupported characters */

    const uint8_t *glyph = font8x8_basic[c - 32];
    uint16_t color_sw = __builtin_bswap16(color);
    uint16_t bg_sw = __builtin_bswap16(bg);

    if (scale == 1) {
        /* Optimized path for scale=1: draw row by row */
        uint16_t row_buf[8];
        for (int row = 0; row < 8; row++) {
            int py = y + row;
            if (py < 0 || py >= DISPLAY_HEIGHT) continue;

            uint8_t line = glyph[row];
            for (int col = 0; col < 8; col++) {
                int px = x + col;
                if (px < 0 || px >= DISPLAY_WIDTH) {
                    row_buf[col] = bg_sw;
                    continue;
                }
                row_buf[col] = (line & (0x80 >> col)) ? color_sw : bg_sw;
            }

            int draw_x = (x < 0) ? 0 : x;
            int draw_w = 8;
            int offset = 0;
            if (x < 0) { offset = -x; draw_w += x; }
            if (draw_x + draw_w > DISPLAY_WIDTH) draw_w = DISPLAY_WIDTH - draw_x;
            if (draw_w > 0) {
                esp_lcd_panel_draw_bitmap(s_panel_handle, draw_x, py,
                                          draw_x + draw_w, py + 1, &row_buf[offset]);
            }
        }
    } else {
        /* Scaled drawing */
        int char_w = 8 * scale;
        uint16_t *scaled_row = (uint16_t *)malloc(char_w * sizeof(uint16_t));
        if (!scaled_row) return;

        for (int row = 0; row < 8; row++) {
            uint8_t line = glyph[row];
            for (int col = 0; col < 8; col++) {
                uint16_t pix = (line & (0x80 >> col)) ? color_sw : bg_sw;
                for (int sx = 0; sx < scale; sx++) {
                    scaled_row[col * scale + sx] = pix;
                }
            }

            for (int sy = 0; sy < scale; sy++) {
                int py = y + row * scale + sy;
                if (py < 0 || py >= DISPLAY_HEIGHT) continue;

                int draw_x = (x < 0) ? 0 : x;
                int draw_w = char_w;
                int offset = 0;
                if (x < 0) { offset = -x; draw_w += x; }
                if (draw_x + draw_w > DISPLAY_WIDTH) draw_w = DISPLAY_WIDTH - draw_x;
                if (draw_w > 0) {
                    esp_lcd_panel_draw_bitmap(s_panel_handle, draw_x, py,
                                              draw_x + draw_w, py + 1, &scaled_row[offset]);
                }
            }
        }
        free(scaled_row);
    }
}

void display_draw_string(int x, int y, const char *str, uint16_t color, uint16_t bg, int scale)
{
    if (s_panel_handle == NULL) return;
    if (str == NULL || scale <= 0) return;

    int char_width = 8 * scale;
    int char_height = 8 * scale;
    uint16_t color_sw = __builtin_bswap16(color);
    uint16_t bg_sw = __builtin_bswap16(bg);

    int char_indices[128];
    int char_count = 0;
    const char *p = str;
    
    while (*p && char_count < 128) {
        char c = *p++;
        if (c < 32 || c > 126) c = '?';
        char_indices[char_count++] = c - 32;
    }
    
    if (char_count == 0) return;

    int draw_x = x;
    int draw_w = char_count * char_width;
    
    int offset_x = 0;
    if (draw_x < 0) {
        offset_x = -draw_x;
        draw_w -= offset_x;
        draw_x = 0;
    }
    if (draw_x + draw_w > DISPLAY_WIDTH) {
        draw_w = DISPLAY_WIDTH - draw_x;
    }

    if (draw_w <= 0) return;

    for (int row = 0; row < char_height; row++) {
        int py = y + row;
        if (py < 0 || py >= DISPLAY_HEIGHT) continue;

        int font_row = row / scale;

        for (int c = 0; c < char_count; c++) {
            int px_start = x + c * char_width;
            uint8_t line = font8x8_basic[char_indices[c]][font_row];
            
            for (int col = 0; col < char_width; col++) {
                int px = px_start + col;
                if (px >= draw_x && px < draw_x + draw_w) {
                    int font_col = col / scale;
                    s_line_buf[px - draw_x] = (line & (0x80 >> font_col)) ? color_sw : bg_sw;
                }
            }
        }
        esp_lcd_panel_draw_bitmap(s_panel_handle, draw_x, py, draw_x + draw_w, py + 1, s_line_buf);
    }
}

void display_draw_hline(int x, int y, int width, uint16_t color)
{
    if (y < 0 || y >= DISPLAY_HEIGHT) return;
    if (x < 0) { width += x; x = 0; }
    if (x + width > DISPLAY_WIDTH) width = DISPLAY_WIDTH - x;
    if (width <= 0) return;

    display_fill_rect(x, y, width, 1, color);
}

void display_set_backlight(bool on)
{
#ifdef CONFIG_HUD_HAS_BACKLIGHT_PIN
    gpio_set_level(PIN_NUM_BLK, on ? 1 : 0);
#endif
}

uint32_t next_utf8_char(const char **str)
{
    const uint8_t *p = (const uint8_t *)*str;
    if (p == NULL || *p == '\0') {
        return 0;
    }

    uint32_t codepoint = 0;
    int bytes = 0;

    if ((*p & 0x80) == 0x00) {
        codepoint = *p;
        bytes = 1;
    } else if ((*p & 0xE0) == 0xC0) {
        codepoint = *p & 0x1F;
        bytes = 2;
    } else if ((*p & 0xF0) == 0xE0) {
        codepoint = *p & 0x0F;
        bytes = 3;
    } else if ((*p & 0xF8) == 0xF0) {
        codepoint = *p & 0x07;
        bytes = 4;
    } else {
        /* Invalid UTF-8 start byte */
        (*str)++;
        return '?';
    }

    for (int i = 1; i < bytes; i++) {
        if (p[i] == '\0') {
            /* Unexpected end of string */
            *str += i;
            return '?';
        }
        if ((p[i] & 0xC0) != 0x80) {
            /* Invalid continuation byte */
            *str += i;
            return '?';
        }
        codepoint = (codepoint << 6) | (p[i] & 0x3F);
    }

    *str += bytes;
    return codepoint;
}

uint32_t next_utf8_char_normalized(const char **str)
{
    uint32_t codepoint = next_utf8_char(str);
    if (codepoint == 0) return 0;

    while (1) {
        const char *peek_str = *str;
        uint32_t next_cp = next_utf8_char(&peek_str);
        if (next_cp == 0) break;

        // Check if next_cp is a combining mark
        bool is_combining = false;
        if ((next_cp >= 0x0300 && next_cp <= 0x036F) || 
            next_cp == 0x1DC0 || next_cp == 0x1DC1 || 
            next_cp == 0x1DF9 || next_cp == 0x20D0 || next_cp == 0x20F0) {
            is_combining = true;
        }

        if (is_combining) {
            // Try to find a mapping (codepoint, next_cp) in vn_nfc_table
            bool found = false;
            for (int i = 0; i < 192; i++) {
                if (vn_nfc_table[i].base == codepoint && vn_nfc_table[i].mark == next_cp) {
                    codepoint = vn_nfc_table[i].composed;
                    *str = peek_str; // Advance string pointer past combining mark
                    found = true;
                    break;
                }
            }
            if (!found) {
                // If it is a combining mark but not in our mapping table,
                // ignore it to prevent drawing a fallback '?' character.
                *str = peek_str;
            }
        } else {
            break;
        }
    }
    return codepoint;
}

int utf8_strlen(const char *str)
{
    if (str == NULL) return 0;
    int len = 0;
    const char *p = str;
    while (next_utf8_char_normalized(&p) != 0) {
        len++;
    }
    return len;
}

void display_draw_char_16x24(int x, int y, uint32_t codepoint, uint16_t color, uint16_t bg)
{
    if (s_panel_handle == NULL) return;
    int index = -1;
    if (codepoint >= 32 && codepoint <= 126) {
        index = codepoint - 32;
    } else {
        // Search in font_16x24_vn_codepoints
        for (int i = 0; i < 134; i++) {
            if (font_16x24_vn_codepoints[i] == codepoint) {
                index = 95 + i;
                break;
            }
        }
    }

    // Fallback to '?' index (which is 63 - 32 = 31)
    if (index < 0 || index >= 229) {
        index = 31;
    }

    const uint16_t *glyph = font_16x24[index];
    uint16_t color_sw = __builtin_bswap16(color);
    uint16_t bg_sw = __builtin_bswap16(bg);

    // Calculate Y offset for baseline correction of Vietnamese characters
    int y_offset = 0;
    if (index >= 95) {
        int bottom_row = -1;
        for (int r = 23; r >= 0; r--) {
            if (glyph[r] != 0) {
                bottom_row = r;
                break;
            }
        }
        if (bottom_row != -1) {
            int target_row = 17;
            if (codepoint == 0x00FD || codepoint == 0x1EF3 || 
                codepoint == 0x1EF7 || codepoint == 0x1EF9 || 
                codepoint == 0x1EF5) {
                target_row = 21; // Lowercase y descender
            }
            y_offset = target_row - bottom_row;
        }
    }

    uint16_t row_buf[16];
    for (int row = 0; row < 24; row++) {
        int py = y + y_offset + row;
        if (py < 0 || py >= DISPLAY_HEIGHT) continue;

        uint16_t line = glyph[row];
        for (int col = 0; col < 16; col++) {
            int px = x + col;
            if (px < 0 || px >= DISPLAY_WIDTH) {
                row_buf[col] = bg_sw;
                continue;
            }
            // Thinner font (horizontal erosion): keep pixel on only if it is on AND its left neighbor is on
            bool pixel_on = (line & (0x8000 >> col)) != 0;
            bool left_on = (col > 0) && ((line & (0x8000 >> (col - 1))) != 0);
            row_buf[col] = (pixel_on && left_on) ? color_sw : bg_sw;
        }

        int draw_x = (x < 0) ? 0 : x;
        int draw_w = 16;
        int offset = 0;
        if (x < 0) { offset = -x; draw_w += x; }
        if (draw_x + draw_w > DISPLAY_WIDTH) draw_w = DISPLAY_WIDTH - draw_x;
        if (draw_w > 0) {
            esp_lcd_panel_draw_bitmap(s_panel_handle, draw_x, py,
                                      draw_x + draw_w, py + 1, &row_buf[offset]);
        }
    }
}

void display_draw_string_16x24(int x, int y, const char *str, uint16_t color, uint16_t bg)
{
    if (s_panel_handle == NULL) return;
    if (str == NULL) return;

    int char_width = 16;
    int char_height = 24;
    uint16_t color_sw = __builtin_bswap16(color);
    uint16_t bg_sw = __builtin_bswap16(bg);

    int char_indices[128];
    int y_offsets[128];
    int char_count = 0;
    const char *p = str;

    while (*p && char_count < 128) {
        uint32_t codepoint = next_utf8_char_normalized(&p);
        if (codepoint == 0) break;

        int index = -1;
        if (codepoint >= 32 && codepoint <= 126) {
            index = codepoint - 32;
        } else {
            for (int i = 0; i < 134; i++) {
                if (font_16x24_vn_codepoints[i] == codepoint) {
                    index = 95 + i;
                    break;
                }
            }
        }
        if (index < 0 || index >= 229) {
            index = 31;
        }
        char_indices[char_count] = index;

        // Calculate Y offset for baseline correction of Vietnamese characters
        int y_offset = 0;
        if (index >= 95) {
            const uint16_t *glyph = font_16x24[index];
            int bottom_row = -1;
            for (int r = 23; r >= 0; r--) {
                if (glyph[r] != 0) {
                    bottom_row = r;
                    break;
                }
            }
            if (bottom_row != -1) {
                int target_row = 17;
                if (codepoint == 0x00FD || codepoint == 0x1EF3 || 
                    codepoint == 0x1EF7 || codepoint == 0x1EF9 || 
                    codepoint == 0x1EF5) {
                    target_row = 21; // Lowercase y descender
                }
                y_offset = target_row - bottom_row;
            }
        }
        y_offsets[char_count] = y_offset;
        char_count++;
    }
    
    if (char_count == 0) return;

    int draw_x = x;
    int draw_w = char_count * char_width;
    
    int offset_x = 0;
    if (draw_x < 0) {
        offset_x = -draw_x;
        draw_w -= offset_x;
        draw_x = 0;
    }
    if (draw_x + draw_w > DISPLAY_WIDTH) {
        draw_w = DISPLAY_WIDTH - draw_x;
    }

    if (draw_w <= 0) return;

    for (int row = 0; row < char_height; row++) {
        int py = y + row;
        if (py < 0 || py >= DISPLAY_HEIGHT) continue;

        for (int c = 0; c < char_count; c++) {
            int px_start = x + c * char_width;
            int glyph_row = row - y_offsets[c];
            uint16_t line = (glyph_row >= 0 && glyph_row < 24) ? font_16x24[char_indices[c]][glyph_row] : 0x0000;
            
            for (int col = 0; col < char_width; col++) {
                int px = px_start + col;
                if (px >= draw_x && px < draw_x + draw_w) {
                    // Thinner font (horizontal erosion)
                    bool pixel_on = (line & (0x8000 >> col)) != 0;
                    bool left_on = (col > 0) && ((line & (0x8000 >> (col - 1))) != 0);
                    s_line_buf[px - draw_x] = (pixel_on && left_on) ? color_sw : bg_sw;
                }
            }
        }
        esp_lcd_panel_draw_bitmap(s_panel_handle, draw_x, py, draw_x + draw_w, py + 1, s_line_buf);
    }
}


void display_draw_digit_48x80(int x, int y, int digit, uint16_t color, uint16_t bg)
{
    if (s_panel_handle == NULL) return;
    if (digit < 0 || digit > 10) return; // 0-9, and 10 is ':'

    const uint8_t *glyph = font_48x80[digit];
    uint16_t color_sw = __builtin_bswap16(color);
    uint16_t bg_sw = __builtin_bswap16(bg);

    uint16_t row_buf[48];
    for (int row = 0; row < 80; row++) {
        int py = y + row;
        if (py < 0 || py >= DISPLAY_HEIGHT) continue;

        for (int byte_idx = 0; byte_idx < 6; byte_idx++) {
            uint8_t byte_val = glyph[row * 6 + byte_idx];
            for (int bit = 0; bit < 8; bit++) {
                int col = byte_idx * 8 + bit;
                int px = x + col;
                if (px < 0 || px >= DISPLAY_WIDTH) {
                    row_buf[col] = bg_sw;
                    continue;
                }
                row_buf[col] = (byte_val & (0x80 >> bit)) ? color_sw : bg_sw;
            }
        }

        int draw_x = (x < 0) ? 0 : x;
        int draw_w = 48;
        int offset = 0;
        if (x < 0) { offset = -x; draw_w += x; }
        if (draw_x + draw_w > DISPLAY_WIDTH) draw_w = DISPLAY_WIDTH - draw_x;
        if (draw_w > 0) {
            esp_lcd_panel_draw_bitmap(s_panel_handle, draw_x, py,
                                      draw_x + draw_w, py + 1, &row_buf[offset]);
        }
    }
}

void display_draw_line(int x0, int y0, int x1, int y1, uint16_t color)
{
    int dx = abs(x1 - x0);
    int dy = -abs(y1 - y0);
    int sx = x0 < x1 ? 1 : -1;
    int sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    
    while (1) {
        display_draw_pixel(x0, y0, color);
        if (x0 == x1 && y0 == y1) break;
        int e2 = 2 * err;
        if (e2 >= dy) {
            err += dy;
            x0 += sx;
        }
        if (e2 <= dx) {
            err += dx;
            y0 += sy;
        }
    }
}

void display_draw_bitmap(int x, int y, int width, int height, const uint16_t *bitmap)
{
    if (s_panel_handle == NULL) return;
    if (x < 0 || y < 0 || x >= DISPLAY_WIDTH || y >= DISPLAY_HEIGHT) return;
    if (width <= 0 || height <= 0) return;

    int w = width;
    int h = height;
    if (x + w > DISPLAY_WIDTH) w = DISPLAY_WIDTH - x;
    if (y + h > DISPLAY_HEIGHT) h = DISPLAY_HEIGHT - y;
    if (w <= 0 || h <= 0) return;

    esp_lcd_panel_draw_bitmap(s_panel_handle, x, y, x + w, y + h, bitmap);

    if (s_display_task_handle != NULL) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    }
}



