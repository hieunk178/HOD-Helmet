import re

with open('main/http_server.c', 'r') as f:
    content = f.read()

# Add WS includes
content = content.replace('#include "esp_http_server.h"', '#include "esp_http_server.h"\n#include "cJSON.h"\nextern httpd_handle_t s_server;')

# Add WS logic
ws_logic = """
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

static httpd_req_t *s_last_ws_req = NULL;

static void wifi_scan_callback(void *results, int count) {
    if (!s_last_ws_req) return;
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
    send_ws_json(s_last_ws_req, root);
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
            ESP_LOGI(TAG, "WS Recv: %s", ws_pkt.payload);
            s_last_ws_req = req;
            cJSON *root = cJSON_Parse((const char *)ws_pkt.payload);
            if (root) {
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
"""

content = content.replace("/* ============================================================\n * URI Definitions\n * ============================================================ */", ws_logic + "\n/* ============================================================\n * URI Definitions\n * ============================================================ */")

content = content.replace("httpd_register_uri_handler(s_server, &uri_post_nav);", "httpd_register_uri_handler(s_server, &uri_post_nav);\n    httpd_register_uri_handler(s_server, &uri_ws);")

with open('main/http_server.c', 'w') as f:
    f.write(content)
