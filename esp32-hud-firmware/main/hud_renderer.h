/*
 * hud_renderer.h - HUD Display Renderer for Helmet
 *
 * Manages the visual layout and rendering of HUD information
 * on the 160x80 TFT display.
 */

#ifndef HUD_RENDERER_H
#define HUD_RENDERER_H

#include "http_server.h"
#include <stdbool.h>

/**
 * @brief Initialize the HUD renderer
 *
 * Sets up initial display state and draws the base layout.
 */
void hud_renderer_init(void);

/**
 * @brief Update the time display
 *
 * @param time_data Pointer to time data structure
 */
void hud_render_time(const hud_time_data_t *time_data);

/**
 * @brief Update the date display
 *
 * @param time_data Pointer to time data structure
 */
void hud_render_date(const hud_time_data_t *time_data);

/**
 * @brief Render a notification on the display
 *
 * @param notif Pointer to notification data
 */
void hud_render_notification(const hud_notification_t *notif);

/**
 * @brief Clear the notification area
 */
void hud_clear_notification_area(void);

/**
 * @brief Render WiFi status indicator
 *
 * @param connected true if WiFi is connected
 * @param ip_address IP address string (shown briefly on connect)
 */
void hud_render_wifi_status(bool connected, const char *ip_address);
void hud_render_header(bool connected, const char *ip_address, int battery, int temp);

/**
 * @brief Render the "Connecting..." boot screen
 *
 * @param ssid WiFi SSID being connected to
 */
void hud_render_boot_screen(const char *ssid);

/**
 * @brief Render "No Data" waiting screen
 */
void hud_render_waiting_screen(void);

/**
 * @brief Render navigation screen
 */
void hud_render_nav_screen(const hud_nav_data_t *nav, const hud_time_data_t *time_data);

/**
 * @brief Render screen streaming screen
 */
void hud_render_stream_screen(bool first_time);

/**
 * @brief Full HUD update cycle
 *
 * Reads current data from http_server and updates all display areas.
 */
void hud_update(void);

#endif /* HUD_RENDERER_H */
