/*
 * http_server.c - HTTP Server Implementation
 *
 * Provides REST API endpoints for the Android app to send
 * time data and notifications to the HUD display.
 */

#include "http_server.h"
#include "wifi_manager.h"
#include "esp_wifi.h"
#include "hud_stream.h"
#include "display_driver.h"

#include "esp_http_server.h"
#include "cJSON.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

#include <string.h>
#include <time.h>
#include <sys/time.h>

static const char *TAG = "http_srv";

/* HTTP server handle */
static httpd_handle_t s_server = NULL;

/* Shared data with mutex protection */
static hud_time_data_t s_time_data = {
    .battery = -1,
    .temp = -999,
    .valid = false
};
static hud_notification_t s_notification = {0};
static hud_mode_t s_hud_mode = HUD_MODE_DEFAULT;
static hud_nav_data_t s_nav_data = {0};
static SemaphoreHandle_t s_data_mutex = NULL;
static int s_ws_fd = -1;  /* WebSocket file descriptor for async sends */

/* Storage for 64x64 RGB565 navigation icon */
uint8_t g_nav_icon_rgb565[8192];
bool g_nav_icon_valid = false;

/* Maximum content length for POST requests */
#define MAX_CONTENT_LEN 512



/* ============================================================
 * WebSocket Handler & Wi-Fi Management
 * ============================================================ */

static void send_ws_json(httpd_req_t *req, cJSON *root) {
    char *str = cJSON_PrintUnformatted(root);
    if (str) {
        httpd_ws_frame_t ws_pkt;
        memset(&ws_pkt, 0, sizeof(httpd_ws_frame_t));
        ws_pkt.payload = (uint8_t*)str;
        ws_pkt.len = strlen(str);
        ws_pkt.type = HTTPD_WS_TYPE_TEXT;
        httpd_ws_send_frame(req, &ws_pkt);
        free(str);
    }
}

/* Async-safe version: sends via fd instead of req pointer */
static void send_ws_json_async(int fd, cJSON *root) {
    if (fd < 0 || s_server == NULL) return;
    char *str = cJSON_PrintUnformatted(root);
    if (str) {
        httpd_ws_frame_t ws_pkt;
        memset(&ws_pkt, 0, sizeof(httpd_ws_frame_t));
        ws_pkt.payload = (uint8_t*)str;
        ws_pkt.len = strlen(str);
        ws_pkt.type = HTTPD_WS_TYPE_TEXT;
        httpd_ws_send_frame_async(s_server, fd, &ws_pkt);
        free(str);
    }
}

static void wifi_scan_callback(void *results, int count) {
    if (s_ws_fd < 0) return;
    wifi_ap_record_t *ap_info = (wifi_ap_record_t *)results;
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "cmd", "wifi_scan_result");
    cJSON *arr = cJSON_AddArrayToObject(root, "networks");
    for (int i=0; i<count; i++) {
        cJSON *item = cJSON_CreateObject();
        cJSON_AddStringToObject(item, "ssid", (char *)ap_info[i].ssid);
        cJSON_AddNumberToObject(item, "rssi", ap_info[i].rssi);
        cJSON_AddItemToArray(arr, item);
    }
    send_ws_json_async(s_ws_fd, root);
    cJSON_Delete(root);
}

static void send_saved_networks(httpd_req_t *req) {
    wifi_credential_t list[WIFI_MAX_SAVED_NETWORKS];
    int count = wifi_manager_get_saved(list, WIFI_MAX_SAVED_NETWORKS);
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "cmd", "wifi_list_saved");
    cJSON *arr = cJSON_AddArrayToObject(root, "networks");
    for (int i=0; i<count; i++) {
        cJSON *item = cJSON_CreateObject();
        cJSON_AddStringToObject(item, "ssid", list[i].ssid);
        cJSON_AddItemToArray(arr, item);
    }
    send_ws_json(req, root);
    cJSON_Delete(root);
}

static esp_err_t ws_handler(httpd_req_t *req) {
    if (req->method == HTTP_GET) {
        ESP_LOGI(TAG, "WebSocket connected");
        return ESP_OK;
    }
    httpd_ws_frame_t ws_pkt;
    uint8_t *buf = NULL;
    memset(&ws_pkt, 0, sizeof(httpd_ws_frame_t));
    ws_pkt.type = HTTPD_WS_TYPE_TEXT;
    
    esp_err_t ret = httpd_ws_recv_frame(req, &ws_pkt, 0);
    if (ret != ESP_OK) return ret;
    
    if (ws_pkt.len) {
        buf = calloc(1, ws_pkt.len + 1);
        if (buf == NULL) return ESP_ERR_NO_MEM;
        ws_pkt.payload = buf;
        ret = httpd_ws_recv_frame(req, &ws_pkt, ws_pkt.len);
        if (ret == ESP_OK) {
            s_ws_fd = httpd_req_to_sockfd(req);
            if (ws_pkt.type == HTTPD_WS_TYPE_BINARY) {
                if (ws_pkt.len > 0 && ws_pkt.payload[0] == 0x01) {
                    if (ws_pkt.len == 8193) { // 1 byte header + 8192 bytes image
                        if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                            memcpy(g_nav_icon_rgb565, ws_pkt.payload + 1, 8192);
                            g_nav_icon_valid = true;
                            xSemaphoreGive(s_data_mutex);
                            ESP_LOGI(TAG, "Received Nav Icon binary (8192 bytes)");
                        }
                    } else {
                        ESP_LOGW(TAG, "Invalid Nav Icon size: %d", ws_pkt.len);
                    }
                }
            } else if (ws_pkt.type == HTTPD_WS_TYPE_TEXT) {
                ESP_LOGI(TAG, "WS Recv: %s", ws_pkt.payload);
                cJSON *root = cJSON_Parse((const char *)ws_pkt.payload);
                if (root == NULL) {
                    ESP_LOGE(TAG, "Failed to parse JSON: %s", (const char *)ws_pkt.payload);
                } else {
                cJSON *cmd = cJSON_GetObjectItem(root, "cmd");
                if (cJSON_IsString(cmd)) {
                    if (strcmp(cmd->valuestring, "wifi_scan") == 0) {
                        wifi_manager_start_scan(wifi_scan_callback);
                    } else if (strcmp(cmd->valuestring, "wifi_list_saved") == 0) {
                        send_saved_networks(req);
                    } else if (strcmp(cmd->valuestring, "wifi_connect") == 0) {
                        cJSON *ssid = cJSON_GetObjectItem(root, "ssid");
                        cJSON *password = cJSON_GetObjectItem(root, "password");
                        if (cJSON_IsString(ssid) && cJSON_IsString(password)) {
                            wifi_manager_connect_new(ssid->valuestring, password->valuestring);
                        }
                    } else if (strcmp(cmd->valuestring, "wifi_delete") == 0) {
                        cJSON *ssid = cJSON_GetObjectItem(root, "ssid");
                        if (cJSON_IsString(ssid)) {
                            wifi_manager_delete_network(ssid->valuestring);
                            send_saved_networks(req); // send updated list
                        }
                    } else if (strcmp(cmd->valuestring, "time") == 0) {
                        cJSON *hour = cJSON_GetObjectItem(root, "hour");
                        cJSON *minute = cJSON_GetObjectItem(root, "minute");
                        cJSON *second = cJSON_GetObjectItem(root, "second");
                        cJSON *day = cJSON_GetObjectItem(root, "day");
                        cJSON *month = cJSON_GetObjectItem(root, "month");
                        cJSON *year = cJSON_GetObjectItem(root, "year");
                        cJSON *weekday = cJSON_GetObjectItem(root, "weekday");
                        cJSON *battery = cJSON_GetObjectItem(root, "battery");
                        cJSON *temp = cJSON_GetObjectItem(root, "temp");
                        if (cJSON_IsNumber(hour) && cJSON_IsNumber(minute) && cJSON_IsNumber(second) &&
                            cJSON_IsNumber(day) && cJSON_IsNumber(month) && cJSON_IsNumber(year)) {
                            struct tm tm_val = {
                                .tm_sec = second->valueint,
                                .tm_min = minute->valueint,
                                .tm_hour = hour->valueint,
                                .tm_mday = day->valueint,
                                .tm_mon = month->valueint - 1,
                                .tm_year = year->valueint - 1900,
                                .tm_isdst = -1
                            };
                            time_t t = mktime(&tm_val);
                            struct timeval tv = { .tv_sec = t, .tv_usec = 0 };
                            settimeofday(&tv, NULL);
                            if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                                s_time_data.hour = hour->valueint;
                                s_time_data.minute = minute->valueint;
                                s_time_data.second = second->valueint;
                                s_time_data.day = day->valueint;
                                s_time_data.month = month->valueint;
                                s_time_data.year = year->valueint;
                                s_time_data.weekday = cJSON_IsNumber(weekday) ? weekday->valueint : 0;
                                s_time_data.battery = cJSON_IsNumber(battery) ? battery->valueint : -1;
                                s_time_data.temp = cJSON_IsNumber(temp) ? temp->valueint : -999;
                                s_time_data.valid = true;
                                xSemaphoreGive(s_data_mutex);
                            }
                        }
                    } else if (strcmp(cmd->valuestring, "notification") == 0) {
                        cJSON *title   = cJSON_GetObjectItem(root, "title");
                        cJSON *message = cJSON_GetObjectItem(root, "message");
                        if (message == NULL || !cJSON_IsString(message)) message = cJSON_GetObjectItem(root, "text");
                        cJSON *icon    = cJSON_GetObjectItem(root, "icon");
                        cJSON *app     = cJSON_GetObjectItem(root, "app");
                        bool has_title   = cJSON_IsString(title) || cJSON_IsString(app);
                        bool has_message = cJSON_IsString(message);
                        if ((has_title || has_message) && xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                            if (cJSON_IsString(title) && title->valuestring[0] != '\0') {
                                strncpy(s_notification.title, title->valuestring, NOTIF_TITLE_MAX_LEN - 1);
                            } else if (cJSON_IsString(app) && app->valuestring[0] != '\0') {
                                strncpy(s_notification.title, app->valuestring, NOTIF_TITLE_MAX_LEN - 1);
                            } else {
                                strncpy(s_notification.title, "Thông báo", NOTIF_TITLE_MAX_LEN - 1);
                            }
                            s_notification.title[NOTIF_TITLE_MAX_LEN - 1] = '\0';
                            if (cJSON_IsString(message)) {
                                strncpy(s_notification.message, message->valuestring, NOTIF_MESSAGE_MAX_LEN - 1);
                            } else {
                                s_notification.message[0] = '\0';
                            }
                            s_notification.message[NOTIF_MESSAGE_MAX_LEN - 1] = '\0';
                            if (cJSON_IsString(icon) && icon->valuestring[0] != '\0') {
                                strncpy(s_notification.icon, icon->valuestring, NOTIF_ICON_MAX_LEN - 1);
                            } else if (cJSON_IsString(app) && app->valuestring[0] != '\0') {
                                strncpy(s_notification.icon, app->valuestring, NOTIF_ICON_MAX_LEN - 1);
                            } else {
                                strcpy(s_notification.icon, "bell");
                            }
                            s_notification.icon[NOTIF_ICON_MAX_LEN - 1] = '\0';
                            s_notification.active = true;
                            s_notification.timestamp = esp_timer_get_time() / 1000;
                            xSemaphoreGive(s_data_mutex);
                        }
                    } else if (strcmp(cmd->valuestring, "clear_notif") == 0) {
                        http_server_clear_notification();
                    } else if (strcmp(cmd->valuestring, "stop_nav") == 0) {
                        hud_stream_reset_active();
                    } else if (strcmp(cmd->valuestring, "mode") == 0) {
                        cJSON *mode = cJSON_GetObjectItem(root, "mode");
                        if (cJSON_IsNumber(mode) && mode->valueint >= 1 && mode->valueint <= 4) {
                            http_server_update_mode((hud_mode_t)mode->valueint);
                        }
                    } else if (strcmp(cmd->valuestring, "nav") == 0) {
                        cJSON *street = cJSON_GetObjectItem(root, "street");
                        cJSON *instruction = cJSON_GetObjectItem(root, "instruction");
                        cJSON *time_left = cJSON_GetObjectItem(root, "time_left");
                        cJSON *distance_left = cJSON_GetObjectItem(root, "distance_left");
                        cJSON *eta = cJSON_GetObjectItem(root, "eta");
                        cJSON *distance = cJSON_GetObjectItem(root, "distance");
                        cJSON *turn_type = cJSON_GetObjectItem(root, "turn_type");
                        cJSON *active = cJSON_GetObjectItem(root, "active");
                        if (cJSON_IsString(street) && cJSON_IsString(instruction) &&
                            cJSON_IsNumber(distance) && cJSON_IsNumber(turn_type)) {
                            hud_nav_data_t nav = {0};
                            strncpy(nav.street, street->valuestring, sizeof(nav.street) - 1);
                            nav.street[sizeof(nav.street) - 1] = '\0';
                            strncpy(nav.instruction, instruction->valuestring, sizeof(nav.instruction) - 1);
                            nav.instruction[sizeof(nav.instruction) - 1] = '\0';
                            
                            if (cJSON_IsString(time_left)) {
                                strncpy(nav.time_left, time_left->valuestring, sizeof(nav.time_left) - 1);
                                nav.time_left[sizeof(nav.time_left) - 1] = '\0';
                            } else {
                                nav.time_left[0] = '\0';
                            }
                            if (cJSON_IsString(distance_left)) {
                                strncpy(nav.distance_left, distance_left->valuestring, sizeof(nav.distance_left) - 1);
                                nav.distance_left[sizeof(nav.distance_left) - 1] = '\0';
                            } else {
                                nav.distance_left[0] = '\0';
                            }
                            if (cJSON_IsString(eta)) {
                                strncpy(nav.eta, eta->valuestring, sizeof(nav.eta) - 1);
                                nav.eta[sizeof(nav.eta) - 1] = '\0';
                            } else {
                                nav.eta[0] = '\0';
                            }

                            nav.distance = distance->valueint;
                            nav.turn_type = turn_type->valueint;
                            nav.active = cJSON_IsBool(active) ? cJSON_IsTrue(active) : true;
                            nav.timestamp = esp_timer_get_time() / 1000;
                            http_server_update_nav(&nav);
                        }
                    } else if (strcmp(cmd->valuestring, "brightness") == 0) {
                        cJSON *brightness = cJSON_GetObjectItem(root, "brightness");
                        if (cJSON_IsNumber(brightness)) {
                            int val = brightness->valueint;
                            if (val < 0) val = 0;
                            if (val > 100) val = 100;
                            display_set_brightness((uint8_t)val);
                            ESP_LOGI(TAG, "Brightness set to %d%%", val);
                        }
                    }
                }
                }
                cJSON_Delete(root);
            }
        }
        free(buf);
    }
    return ret;
}

static const httpd_uri_t uri_ws = {
    .uri        = "/ws",
    .method     = HTTP_GET,
    .handler    = ws_handler,
    .user_ctx   = NULL,
    .is_websocket = true
};



/* ============================================================
 * Public API
 * ============================================================ */
esp_err_t http_server_start(void)
{
    if (s_server != NULL) {
        ESP_LOGW(TAG, "HTTP server already running");
        return ESP_OK;
    }

    /* Create mutex for shared data */
    if (s_data_mutex == NULL) {
        s_data_mutex = xSemaphoreCreateMutex();
        if (s_data_mutex == NULL) {
            ESP_LOGE(TAG, "Failed to create mutex");
            return ESP_FAIL;
        }
    }

    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.max_uri_handlers = 8;
    config.stack_size = 8192;

    ESP_LOGI(TAG, "Starting HTTP server on port %d", config.server_port);
    esp_err_t ret = httpd_start(&s_server, &config);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start HTTP server: %s", esp_err_to_name(ret));
        return ret;
    }

    /* Register URI handlers */
    httpd_register_uri_handler(s_server, &uri_ws);

    ESP_LOGI(TAG, "HTTP server started successfully");
    ESP_LOGI(TAG, "  WS   /ws                - WebSocket interface");

    return ESP_OK;
}

void http_server_stop(void)
{
    if (s_server) {
        httpd_stop(s_server);
        s_server = NULL;
        ESP_LOGI(TAG, "HTTP server stopped");
    }
}

bool http_server_get_time(hud_time_data_t *out)
{
    if (out == NULL) return false;

    time_t now;
    struct tm timeinfo;
    time(&now);
    localtime_r(&now, &timeinfo);

    out->hour = timeinfo.tm_hour;
    out->minute = timeinfo.tm_min;
    out->second = timeinfo.tm_sec;
    out->day = timeinfo.tm_mday;
    out->month = timeinfo.tm_mon + 1;
    out->year = timeinfo.tm_year + 1900;
    out->weekday = timeinfo.tm_wday;

    bool valid = false;
    if (s_data_mutex && xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        out->battery = s_time_data.battery;
        out->temp = s_time_data.temp;
        out->valid = s_time_data.valid;
        valid = s_time_data.valid;
        xSemaphoreGive(s_data_mutex);
    } else {
        out->battery = -1;
        out->temp = -999;
        out->valid = false;
        valid = false;
    }

    return valid;
}

bool http_server_get_notification(hud_notification_t *out)
{
    if (out == NULL) return false;
    if (s_data_mutex == NULL) {
        ESP_LOGE(TAG, "http_server_get_notification: s_data_mutex is NULL!");
        return false;
    }

    if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        *out = s_notification;
        xSemaphoreGive(s_data_mutex);
        return out->active;
    } else {
        ESP_LOGW(TAG, "http_server_get_notification: failed to take mutex!");
    }
    return false;
}

void http_server_clear_notification(void)
{
    if (s_data_mutex == NULL) {
        ESP_LOGE(TAG, "http_server_clear_notification: s_data_mutex is NULL!");
        return;
    }

    if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        memset(&s_notification, 0, sizeof(s_notification));
        s_notification.active = false;
        xSemaphoreGive(s_data_mutex);
        ESP_LOGI(TAG, "Notification cleared");
    } else {
        ESP_LOGE(TAG, "http_server_clear_notification: failed to take mutex!");
    }
}

void http_server_update_time(const hud_time_data_t *time_data)
{
    if (time_data == NULL) return;
    if (s_data_mutex == NULL) {
        ESP_LOGE(TAG, "http_server_update_time: s_data_mutex is NULL!");
        return;
    }

    if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_time_data = *time_data;
        s_time_data.valid = true;
        xSemaphoreGive(s_data_mutex);
    } else {
        ESP_LOGE(TAG, "http_server_update_time: failed to take mutex!");
    }
}

void http_server_update_notification(const hud_notification_t *notif)
{
    if (notif == NULL) return;
    if (s_data_mutex == NULL) {
        ESP_LOGE(TAG, "http_server_update_notification: s_data_mutex is NULL!");
        return;
    }

    if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_notification = *notif;
        xSemaphoreGive(s_data_mutex);
        ESP_LOGI(TAG, "http_server_update_notification: s_notification updated successfully! active=%d", s_notification.active);
    } else {
        ESP_LOGE(TAG, "http_server_update_notification: failed to take mutex!");
    }
}

hud_mode_t http_server_get_mode(void)
{
    hud_mode_t mode = HUD_MODE_DEFAULT;
    if (s_data_mutex && xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        mode = s_hud_mode;
        xSemaphoreGive(s_data_mutex);
    }
    return mode;
}

void http_server_update_mode(hud_mode_t mode)
{
    if (s_data_mutex && xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_hud_mode = mode;
        xSemaphoreGive(s_data_mutex);
        ESP_LOGI(TAG, "HUD Operating Mode updated: %d", (int)mode);
        
        if (mode != HUD_MODE_STREAM && mode != HUD_MODE_MAP_NAV) {
            hud_stream_reset_active();
        }
    }
}

bool http_server_get_nav(hud_nav_data_t *out)
{
    if (out == NULL) return false;
    if (s_data_mutex == NULL) return false;

    if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        *out = s_nav_data;
        xSemaphoreGive(s_data_mutex);
        return out->active;
    }
    return false;
}

void http_server_update_nav(const hud_nav_data_t *nav)
{
    if (nav == NULL) return;
    if (s_data_mutex == NULL) return;

    if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_nav_data = *nav;
        xSemaphoreGive(s_data_mutex);
        ESP_LOGI(TAG, "Navigation instructions updated: street='%s', inst='%s', dist=%d, turn=%d, active=%d",
                 s_nav_data.street, s_nav_data.instruction, s_nav_data.distance, s_nav_data.turn_type, s_nav_data.active);
    }
}

void http_server_clear_nav(void)
{
    if (s_data_mutex == NULL) return;

    if (xSemaphoreTake(s_data_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        memset(&s_nav_data, 0, sizeof(s_nav_data));
        s_nav_data.active = false;
        xSemaphoreGive(s_data_mutex);
        ESP_LOGI(TAG, "Navigation instructions cleared");
    }
}
