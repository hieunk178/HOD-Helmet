/*
 * wifi_manager.c - Smart WiFi Manager with Fallback
 */

#include "wifi_manager.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "nvs.h"
#include <string.h>

static const char *TAG = "wifi_mgr";

static wifi_state_t s_state = WIFI_STATE_INIT;
static char s_ip_address[16] = "0.0.0.0";
static wifi_credential_t s_saved_networks[WIFI_MAX_SAVED_NETWORKS];
static int s_num_saved = 0;
static int s_current_try_index = 0;
static esp_netif_t *s_netif_sta = NULL;
static esp_netif_t *s_netif_ap = NULL;

static wifi_scan_cb_t s_scan_cb = NULL;

static void load_networks_from_nvs(void) {
    nvs_handle_t nvs;
    if (nvs_open("wifi_cfg", NVS_READONLY, &nvs) == ESP_OK) {
        size_t len = sizeof(s_saved_networks);
        if (nvs_get_blob(nvs, "networks", s_saved_networks, &len) == ESP_OK) {
            s_num_saved = len / sizeof(wifi_credential_t);
            ESP_LOGI(TAG, "Loaded %d networks from NVS", s_num_saved);
        }
        nvs_close(nvs);
    }
}

static void save_networks_to_nvs(void) {
    nvs_handle_t nvs;
    if (nvs_open("wifi_cfg", NVS_READWRITE, &nvs) == ESP_OK) {
        nvs_set_blob(nvs, "networks", s_saved_networks, s_num_saved * sizeof(wifi_credential_t));
        nvs_commit(nvs);
        nvs_close(nvs);
    }
}

static void start_softap(void) {
    ESP_LOGI(TAG, "Starting SoftAP mode");
    s_state = WIFI_STATE_AP_MODE;
    esp_wifi_stop();
    esp_wifi_set_mode(WIFI_MODE_APSTA);
    
    wifi_config_t ap_config = {
        .ap = {
            .ssid = "HOD_Helmet",
            .ssid_len = strlen("HOD_Helmet"),
            .channel = 1,
            .password = "12345678",
            .max_connection = 4,
            .authmode = WIFI_AUTH_WPA2_PSK,
            .pmf_cfg = {
                .required = false,
            },
        },
    };
    esp_wifi_set_config(WIFI_IF_AP, &ap_config);
    esp_wifi_start();
    strcpy(s_ip_address, "192.168.4.1");
}

static void try_connect_next(void) {
    if (s_current_try_index >= s_num_saved) {
        ESP_LOGW(TAG, "All saved networks failed. Falling back to SoftAP.");
        start_softap();
        return;
    }
    
    ESP_LOGI(TAG, "Trying to connect to %s", s_saved_networks[s_current_try_index].ssid);
    s_state = WIFI_STATE_CONNECTING;
    esp_wifi_stop();
    esp_wifi_set_mode(WIFI_MODE_STA);
    
    wifi_config_t sta_config = {0};
    strlcpy((char *)sta_config.sta.ssid, s_saved_networks[s_current_try_index].ssid, sizeof(sta_config.sta.ssid));
    strlcpy((char *)sta_config.sta.password, s_saved_networks[s_current_try_index].password, sizeof(sta_config.sta.password));
    
    esp_wifi_set_config(WIFI_IF_STA, &sta_config);
    esp_wifi_start();
}

static void wifi_event_handler(void* arg, esp_event_base_t event_base,
                               int32_t event_id, void* event_data) {
    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        if (s_state == WIFI_STATE_CONNECTING) {
            ESP_LOGW(TAG, "Connect failed to %s", s_saved_networks[s_current_try_index].ssid);
            s_current_try_index++;
            try_connect_next();
        } else if (s_state == WIFI_STATE_CONNECTED_STA) {
            ESP_LOGW(TAG, "Disconnected from STA. Reconnecting...");
            esp_wifi_connect();
        }
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t* event = (ip_event_got_ip_t*) event_data;
        snprintf(s_ip_address, sizeof(s_ip_address), IPSTR, IP2STR(&event->ip_info.ip));
        ESP_LOGI(TAG, "Connected! IP: %s", s_ip_address);
        s_state = WIFI_STATE_CONNECTED_STA;
        
        // Move connected network to front of list (index 0)
        if (s_current_try_index > 0) {
            wifi_credential_t temp = s_saved_networks[s_current_try_index];
            for (int i = s_current_try_index; i > 0; i--) {
                s_saved_networks[i] = s_saved_networks[i-1];
            }
            s_saved_networks[0] = temp;
            save_networks_to_nvs();
        }
        s_current_try_index = 0;
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_SCAN_DONE) {
        uint16_t ap_count = 0;
        esp_wifi_scan_get_ap_num(&ap_count);
        wifi_ap_record_t *ap_info = malloc(sizeof(wifi_ap_record_t) * ap_count);
        esp_wifi_scan_get_ap_records(&ap_count, ap_info);
        
        if (s_scan_cb) {
            s_scan_cb(ap_info, ap_count);
            s_scan_cb = NULL;
        }
        free(ap_info);
    }
}

esp_err_t wifi_manager_init(void) {
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    
    s_netif_sta = esp_netif_create_default_wifi_sta();
    s_netif_ap = esp_netif_create_default_wifi_ap();
    
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    
    esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &wifi_event_handler, NULL, NULL);
    esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP, &wifi_event_handler, NULL, NULL);
    
    load_networks_from_nvs();
    
    if (s_num_saved == 0) {
        start_softap();
    } else {
        s_current_try_index = 0;
        try_connect_next();
    }
    return ESP_OK;
}

wifi_state_t wifi_manager_get_state(void) {
    return s_state;
}

void wifi_manager_get_ip(char *ip_buf, int buf_len) {
    if (ip_buf && buf_len > 0) {
        strlcpy(ip_buf, s_ip_address, buf_len);
    }
}

int wifi_manager_get_saved(wifi_credential_t *out_list, int max_len) {
    int count = (s_num_saved < max_len) ? s_num_saved : max_len;
    for(int i=0; i<count; i++){
        out_list[i] = s_saved_networks[i];
    }
    return count;
}

esp_err_t wifi_manager_save_network(const char *ssid, const char *password) {
    // Check if exists
    for (int i=0; i<s_num_saved; i++) {
        if (strcmp(s_saved_networks[i].ssid, ssid) == 0) {
            strlcpy(s_saved_networks[i].password, password, sizeof(s_saved_networks[i].password));
            save_networks_to_nvs();
            return ESP_OK;
        }
    }
    // Add new
    if (s_num_saved < WIFI_MAX_SAVED_NETWORKS) {
        strlcpy(s_saved_networks[s_num_saved].ssid, ssid, sizeof(s_saved_networks[0].ssid));
        strlcpy(s_saved_networks[s_num_saved].password, password, sizeof(s_saved_networks[0].password));
        s_num_saved++;
    } else {
        // Replace last one
        strlcpy(s_saved_networks[WIFI_MAX_SAVED_NETWORKS-1].ssid, ssid, sizeof(s_saved_networks[0].ssid));
        strlcpy(s_saved_networks[WIFI_MAX_SAVED_NETWORKS-1].password, password, sizeof(s_saved_networks[0].password));
    }
    save_networks_to_nvs();
    return ESP_OK;
}

esp_err_t wifi_manager_delete_network(const char *ssid) {
    int found = -1;
    for (int i=0; i<s_num_saved; i++) {
        if (strcmp(s_saved_networks[i].ssid, ssid) == 0) {
            found = i;
            break;
        }
    }
    if (found != -1) {
        for (int i = found; i < s_num_saved - 1; i++) {
            s_saved_networks[i] = s_saved_networks[i+1];
        }
        s_num_saved--;
        save_networks_to_nvs();
    }
    return ESP_OK;
}

esp_err_t wifi_manager_connect_new(const char *ssid, const char *password) {
    wifi_manager_delete_network(ssid); // remove if exists
    // shift all right
    if (s_num_saved < WIFI_MAX_SAVED_NETWORKS) {
        for (int i=s_num_saved; i>0; i--) {
            s_saved_networks[i] = s_saved_networks[i-1];
        }
        s_num_saved++;
    } else {
        for (int i=WIFI_MAX_SAVED_NETWORKS-1; i>0; i--) {
            s_saved_networks[i] = s_saved_networks[i-1];
        }
    }
    strlcpy(s_saved_networks[0].ssid, ssid, sizeof(s_saved_networks[0].ssid));
    strlcpy(s_saved_networks[0].password, password, sizeof(s_saved_networks[0].password));
    save_networks_to_nvs();
    
    s_current_try_index = 0;
    try_connect_next();
    return ESP_OK;
}

esp_err_t wifi_manager_start_scan(wifi_scan_cb_t cb) {
    s_scan_cb = cb;
    wifi_scan_config_t scan_config = {
        .ssid = 0,
        .bssid = 0,
        .channel = 0,
        .show_hidden = false
    };
    return esp_wifi_scan_start(&scan_config, false); // async
}
