/*
 * display_driver.h - TFT ST7735 Display Driver for HUD Helmet
 *
 * Provides low-level display operations: initialization, pixel drawing,
 * text rendering, and backlight control via SPI interface.
 */

#ifndef DISPLAY_DRIVER_H
#define DISPLAY_DRIVER_H

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

/* Display dimensions from Kconfig (ST7735: 160x80, ILI9341: 240x320) */
#ifdef CONFIG_HUD_DISPLAY_WIDTH
#define DISPLAY_WIDTH   CONFIG_HUD_DISPLAY_WIDTH
#define DISPLAY_HEIGHT  CONFIG_HUD_DISPLAY_HEIGHT
#else
#define DISPLAY_WIDTH   160
#define DISPLAY_HEIGHT  80
#endif

/* Pin definitions - adjust to match your wiring */
#define PIN_NUM_SCLK    18
#define PIN_NUM_MOSI    11
#define PIN_NUM_RST     4
#define PIN_NUM_DC      2
#define PIN_NUM_CS      -1   /* Màn không có CS pin */
#define PIN_NUM_BLK     15

/* Common RGB565 colors */
#define COLOR_BLACK     0x0000
#define COLOR_WHITE     0xFFFF
#define COLOR_RED       0xF800
#define COLOR_GREEN     0x07E0
#define COLOR_BLUE      0x001F
#define COLOR_YELLOW    0xFFE0
#define COLOR_CYAN      0x07FF
#define COLOR_MAGENTA   0xF81F
#define COLOR_ORANGE    0xFD20
#define COLOR_DARK_GRAY 0x2104
#define COLOR_LIGHT_GRAY 0x8410
#define COLOR_DARK_BLUE 0x0011
#define COLOR_TEAL      0x0410
#define COLOR_HUD_BG    0x0000   /* Black background for HUD */
#define COLOR_HUD_TIME  0x07FF   /* Cyan for time display */
#define COLOR_HUD_DATE  0x8410   /* Light gray for date */
#define COLOR_HUD_NOTIF 0xFFE0   /* Yellow for notifications */
#define COLOR_HUD_TITLE 0xF81F   /* Magenta for notification title */
#define COLOR_HUD_WIFI  0x07E0   /* Green for WiFi connected */
#define COLOR_HUD_WIFI_OFF 0xF800 /* Red for WiFi disconnected */

/**
 * @brief Initialize the TFT display
 *
 * Sets up SPI bus, installs ST7735 panel driver, and turns on backlight.
 *
 * @return ESP_OK on success
 */
esp_err_t display_init(void);

/**
 * @brief Fill the entire display with a single color
 *
 * @param color RGB565 color value
 */
void display_clear(uint16_t color);

/**
 * @brief Fill a rectangular area with a single color
 *
 * @param x      Start X coordinate
 * @param y      Start Y coordinate
 * @param width  Rectangle width
 * @param height Rectangle height
 * @param color  RGB565 color value
 */
void display_fill_rect(int x, int y, int width, int height, uint16_t color);

/**
 * @brief Draw a single pixel
 *
 * @param x     X coordinate
 * @param y     Y coordinate
 * @param color RGB565 color value
 */
void display_draw_pixel(int x, int y, uint16_t color);

/**
 * @brief Draw a single character using 8x8 font
 *
 * @param x     X coordinate (top-left)
 * @param y     Y coordinate (top-left)
 * @param c     Character to draw
 * @param color Foreground color (RGB565)
 * @param bg    Background color (RGB565)
 * @param scale Scale factor (1 = 8x8, 2 = 16x16, etc.)
 */
void display_draw_char(int x, int y, char c, uint16_t color, uint16_t bg, int scale);

/**
 * @brief Draw a null-terminated string
 *
 * @param x     Starting X coordinate
 * @param y     Starting Y coordinate
 * @param str   Null-terminated string
 * @param color Foreground color (RGB565)
 * @param bg    Background color (RGB565)
 * @param scale Scale factor
 */
void display_draw_string(int x, int y, const char *str, uint16_t color, uint16_t bg, int scale);
/**
 * @brief Decode the next UTF-8 character codepoint from the string
 *
 * @param str Pointer to string pointer. Advanced by number of bytes read.
 * @return 32-bit Unicode codepoint, or 0 if string end.
 */
uint32_t next_utf8_char(const char **str);

/**
 * @brief Decode the next UTF-8 character, combining Vietnamese NFD (decomposed) tones into NFC (composed) codepoints.
 *
 * @param str Pointer to string pointer. Advanced by number of bytes read.
 * @return 32-bit Unicode codepoint, or 0 if string end.
 */
uint32_t next_utf8_char_normalized(const char **str);

/**
 * @brief Get length of a UTF-8 string in characters
 *
 * @param str UTF-8 string
 * @return Character count
 */
int utf8_strlen(const char *str);

/**
 * @brief Draw a single character using high-quality 16x24 font
 *
 * @param x     X coordinate (top-left)
 * @param y     Y coordinate (top-left)
 * @param codepoint Character Unicode codepoint to draw
 * @param color Foreground color (RGB565)
 * @param bg    Background color (RGB565)
 */
void display_draw_char_16x24(int x, int y, uint32_t codepoint, uint16_t color, uint16_t bg);

/**
 * @brief Draw a string using high-quality 16x24 font
 *
 * @param x     Starting X coordinate
 * @param y     Starting Y coordinate
 * @param str   Null-terminated string
 * @param color Foreground color (RGB565)
 * @param bg    Background color (RGB565)
 */
void display_draw_string_16x24(int x, int y, const char *str, uint16_t color, uint16_t bg);


/**
 * @brief Draw a large clock digit using 48x80 font
 *
 * @param x     Starting X coordinate
 * @param y     Starting Y coordinate
 * @param digit Digit to draw (0-9, or 10 for ':')
 * @param color Foreground color (RGB565)
 * @param bg    Background color (RGB565)
 */
void display_draw_digit_48x80(int x, int y, int digit, uint16_t color, uint16_t bg);


/**
 * @brief Draw a horizontal line
 *
 * @param x     Start X
 * @param y     Y coordinate
 * @param width Line width
 * @param color RGB565 color
 */
void display_draw_hline(int x, int y, int width, uint16_t color);

/**
 * @brief Draw an arbitrary line using Bresenham's algorithm
 *
 * @param x0    Start X coordinate
 * @param y0    Start Y coordinate
 * @param x1    End X coordinate
 * @param y1    End Y coordinate
 * @param color RGB565 color
 */
void display_draw_line(int x0, int y0, int x1, int y1, uint16_t color);

/**
 * @brief Control the backlight
 *
 * @param on true to turn on, false to turn off
 */
void display_set_backlight(bool on);

/**
 * @brief Draw a bitmap directly to a rectangular area on the screen
 *
 * @param x      Start X coordinate
 * @param y      Start Y coordinate
 * @param width  Bitmap width
 * @param height Bitmap height
 * @param bitmap Pointer to RGB565 pixel buffer
 */
void display_draw_bitmap(int x, int y, int width, int height, const uint16_t *bitmap);

/**
 * @brief Convert RGB values to RGB565 format
 *
 * @param r Red (0-255)
 * @param g Green (0-255)
 * @param b Blue (0-255)
 * @return RGB565 color value
 */
static inline uint16_t display_rgb565(uint8_t r, uint8_t g, uint8_t b) {
    return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
}

#endif /* DISPLAY_DRIVER_H */
