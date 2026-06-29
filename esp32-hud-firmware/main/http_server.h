/*
 * http_server.h - HTTP Server for receiving data from Android app
 */

#ifndef HTTP_SERVER_H
#define HTTP_SERVER_H

#include "esp_err.h"
#include <stdint.h>
#include <stdbool.h>

/* Maximum lengths for notification data */
#define NOTIF_TITLE_MAX_LEN   32
#define NOTIF_MESSAGE_MAX_LEN 128
#define NOTIF_ICON_MAX_LEN    16

/* Time data received from Android */
typedef struct {
    int hour;
    int minute;
    int second;
    int day;
    int month;
    int year;
    int weekday;  /* 0=Sun, 1=Mon, ... 6=Sat */
    int battery;  /* 0-100%, or -1 if not available */
    int temp;     /* Temperature in Celsius, or -999 if not available */
    bool valid;
} hud_time_data_t;

/* Notification data received from Android */
typedef struct {
    char title[NOTIF_TITLE_MAX_LEN];
    char message[NOTIF_MESSAGE_MAX_LEN];
    char icon[NOTIF_ICON_MAX_LEN];
    bool active;
    int64_t timestamp;  /* Time when notification was received (uptime ms) */
} hud_notification_t;

/**
 * @brief Start the HTTP server
 *
 * @return ESP_OK on success
 */
esp_err_t http_server_start(void);

extern uint8_t g_nav_icon_rgb565[8192];
extern bool g_nav_icon_valid;

/**
 * @brief Stop the HTTP server
 */
void http_server_stop(void);

/**
 * @brief Get the latest time data (thread-safe copy)
 *
 * @param out Pointer to time data structure to fill
 * @return true if valid time data is available
 */
bool http_server_get_time(hud_time_data_t *out);

/**
 * @brief Get the latest notification (thread-safe copy)
 *
 * @param out Pointer to notification structure to fill
 * @return true if an active notification exists
 */
bool http_server_get_notification(hud_notification_t *out);

/**
 * @brief Clear the current notification
 */
void http_server_clear_notification(void);

/**
 * @brief Update the shared time data (thread-safe)
 *
 * @param time_data New time data to set
 */
void http_server_update_time(const hud_time_data_t *time_data);

/**
 * @brief Update the active notification (thread-safe)
 *
 * @param notif New notification to set
 */
void http_server_update_notification(const hud_notification_t *notif);

/* HUD Operating Modes */
typedef enum {
    HUD_MODE_DEFAULT = 1,
    HUD_MODE_NAV = 2,
    HUD_MODE_STREAM = 3,
    HUD_MODE_MAP_NAV = 4
} hud_mode_t;

/* Navigation Instructions from Phone */
typedef struct {
    char street[64];
    char instruction[64];
    char time_left[32];
    char distance_left[32];
    char eta[32];
    int distance;       /* In meters */
    int turn_type;      /* 1=STRAIGHT, 2=LEFT, 3=RIGHT, 4=U_TURN */
    int speed;          /* Current speed in km/h */
    bool active;
    uint64_t timestamp; /* System uptime timestamp in ms */
} hud_nav_data_t;

/**
 * @brief Get the current HUD operating mode (thread-safe)
 */
hud_mode_t http_server_get_mode(void);

/**
 * @brief Update the current HUD operating mode (thread-safe)
 */
void http_server_update_mode(hud_mode_t mode);

/**
 * @brief Get the latest navigation instructions (thread-safe copy)
 *
 * @param out Pointer to navigation structure to fill
 * @return true if navigation is active and valid
 */
bool http_server_get_nav(hud_nav_data_t *out);

/**
 * @brief Update the active navigation instructions (thread-safe)
 */
void http_server_update_nav(const hud_nav_data_t *nav);

/**
 * @brief Clear the current navigation instructions
 */
void http_server_clear_nav(void);

#endif /* HTTP_SERVER_H */
