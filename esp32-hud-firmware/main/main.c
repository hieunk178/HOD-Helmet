/*
 * main.c - HUD Helmet Firmware Entry Point
 *
 * ESP32 + TFT ST7735 0.96" Heads-Up Display for motorcycle helmet.
 * Receives time and notification data from Android app via WiFi HTTP.
 *
 * System initialization order:
 * 1. NVS Flash
 * 2. TFT Display (SPI)
 * 3. Boot screen
 * 4. WiFi connection
 * 5. HTTP server
 * 6. Main rendering loop
 */

#include <stdio.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_log.h"
#include "esp_timer.h"

#include "display_driver.h"
#include "wifi_manager.h"
#include "http_server.h"
#include "hud_renderer.h"
#include "hud_stream.h"

#include "lvgl.h"

static const char *TAG = "hud_main";

/* Main rendering task */
static void hud_main_task(void *pvParameter)
{
    ESP_LOGI(TAG, "HUD main task started");

    bool first_update = true;
    bool has_time_data = false;
    uint32_t last_data_update = 0;

    while (1) {
        /* Run LVGL task handler for rendering and animations, but PAUSE it when streaming to prevent SPI bus contention with JPEG decoder */
        hud_mode_t current_mode = http_server_get_mode();
        if (current_mode != HUD_MODE_STREAM && current_mode != HUD_MODE_MAP_NAV) {
            lv_timer_handler();
        } else {
            // In stream modes, only pause LVGL if stream is actively decoding frames
            if (!hud_stream_is_active()) {
                lv_timer_handler();
            }
        }

        uint32_t now = xTaskGetTickCount() * portTICK_PERIOD_MS;
        if (now - last_data_update >= 500) {
            last_data_update = now;

            /* Check if we have time data yet */
            hud_time_data_t time_check;
            if (http_server_get_time(&time_check)) {
                if (!has_time_data) {
                    /* First time receiving data - clear boot screen artifacts */
                    hud_renderer_init();
                    has_time_data = true;
                    first_update = true;
                }
            }

            if (has_time_data) {
                /* Normal update cycle */
                hud_update();
            } else if (first_update && (wifi_manager_get_state() == WIFI_STATE_CONNECTED_STA || wifi_manager_get_state() == WIFI_STATE_AP_MODE)) {
                /* WiFi connected but no data yet - show waiting screen */
                hud_render_waiting_screen();
                first_update = false;
            }
        }

        /* Delay 20ms for smooth UI animations */
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}


void app_main(void)
{
    ESP_LOGI(TAG, "========================================");
    ESP_LOGI(TAG, "   HUD Helmet Firmware v1.0.0");
    ESP_LOGI(TAG, "   ESP32 + ST7735 TFT 160x80");
    ESP_LOGI(TAG, "========================================");

    /* Initialize system time to 12:00:00 on Friday, 19/06/2026 */
    struct tm tm_boot = {
        .tm_sec = 0,
        .tm_min = 0,
        .tm_hour = 12,
        .tm_mday = 19,
        .tm_mon = 5,      /* June (0-11) */
        .tm_year = 126,    /* 2026 - 1900 */
        .tm_isdst = -1
    };
    time_t t_boot = mktime(&tm_boot);
    struct timeval tv_boot = { .tv_sec = t_boot, .tv_usec = 0 };
    settimeofday(&tv_boot, NULL);
    ESP_LOGI(TAG, "System clock initialized to default 2026-06-19 12:00:00");

    /* Step 1: Initialize NVS (required for WiFi) */
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS partition erased, reinitializing");
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
    ESP_LOGI(TAG, "[1/5] NVS Flash initialized");

    /* Step 2: Initialize display */
    ret = display_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Display init failed! Check wiring.");
        /* Continue without display - WiFi/HTTP still works */
    } else {
        ESP_LOGI(TAG, "[2/5] Display initialized (160x80 ST7735)");
    }

    /* Step 3: Show boot screen */
    hud_render_boot_screen(CONFIG_ESP_WIFI_SSID);
    ESP_LOGI(TAG, "[3/5] Boot screen displayed");

    /* Step 4: Connect to WiFi */
    ESP_LOGI(TAG, "[4/5] Starting Smart WiFi Manager...");
    ret = wifi_manager_init();
    if (ret == ESP_OK) {
        hud_renderer_init();
        hud_render_wifi_status(false, NULL);
    } else {
        ESP_LOGE(TAG, "[4/5] WiFi manager failed to initialize.");
    }

    /* Step 5: Start HTTP server */
    ret = http_server_start();
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "[5/5] HTTP server started");
    } else {
        ESP_LOGE(TAG, "[5/5] HTTP server failed to start");
    }

    /* Initialize Screen Streaming UDP/Display Tasks */
    hud_stream_init();
    ESP_LOGI(TAG, "[6/6] Screen streaming initialization complete");



    ESP_LOGI(TAG, "========================================");
    ESP_LOGI(TAG, "   Initialization complete!");
    ESP_LOGI(TAG, "   Open Android app and connect.");
    ESP_LOGI(TAG, "========================================");

    /* Start the main rendering task */
    xTaskCreate(hud_main_task, "hud_main", 8192, NULL, 5, NULL);
}
