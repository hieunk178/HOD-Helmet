/*
 * wifi_manager.h - Smart WiFi Manager with Fallback
 */

#ifndef WIFI_MANAGER_H
#define WIFI_MANAGER_H

#include "esp_err.h"
#include <stdbool.h>

#define WIFI_MAX_SAVED_NETWORKS 5

typedef struct {
    char ssid[33];
    char password[65];
} wifi_credential_t;

typedef enum {
    WIFI_STATE_INIT = 0,
    WIFI_STATE_CONNECTING,
    WIFI_STATE_CONNECTED_STA,
    WIFI_STATE_AP_MODE,
} wifi_state_t;

esp_err_t wifi_manager_init(void);
wifi_state_t wifi_manager_get_state(void);
void wifi_manager_get_ip(char *ip_buf, int buf_len);
void wifi_manager_get_ssid(char *ssid_buf, int buf_len);

/* API for WebSockets */
int wifi_manager_get_saved(wifi_credential_t *out_list, int max_len);
esp_err_t wifi_manager_save_network(const char *ssid, const char *password);
esp_err_t wifi_manager_delete_network(const char *ssid);

/* Initiate an async connection to a new network, saves it on success */
esp_err_t wifi_manager_connect_new(const char *ssid, const char *password);

/* Wi-Fi Scan API */
typedef void (*wifi_scan_cb_t)(void *results, int count);
esp_err_t wifi_manager_start_scan(wifi_scan_cb_t cb);

#endif /* WIFI_MANAGER_H */
