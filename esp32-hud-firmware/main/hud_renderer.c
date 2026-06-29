/*
 * hud_renderer.c - LVGL Heads-Up Display Renderer implementation
 */

#include "hud_renderer.h"
#include "display_driver.h"
#include "wifi_manager.h"
#include "ui.h"
#include "esp_log.h"

#include "esp_timer.h"

#include <stdio.h>
#include <string.h>
#include "icons.h"
#include "http_server.h"
#include "hud_stream.h"

static const char *TAG = "hud_render";

/* Weekday names */
static const char *weekday_names[] = {
    "CN", "T2", "T3", "T4", "T5", "T6", "T7"
};

/* Cache structure to prevent excessive UI updates */
static lv_obj_t* s_Notif_Img_Icon = NULL;
static struct {
    int last_hour, last_minute, last_second;
    int last_day, last_month, last_year;
    bool last_wifi_state;
    int last_battery;
    int last_temp;
    bool last_notif_active;
    char last_notif_title[NOTIF_TITLE_MAX_LEN];
    char last_notif_msg[NOTIF_MESSAGE_MAX_LEN];
    bool initialized;
/* Navigation caching */
    bool last_nav_active;
    char last_nav_street[64];
    char last_nav_time_left[32];
    char last_nav_distance_left[32];
    char last_nav_eta[32];
    int last_nav_distance;
    int last_nav_turn_type;
    bool last_nav_icon_valid;
} s_prev = {0};

/* Image descriptor for the 64x64 raw RGB565 icon */
static lv_img_dsc_t s_nav_img_dsc = {
    .header.always_zero = 0,
    .header.w = 64,
    .header.h = 64,
    .data_size = 8192,
    .header.cf = LV_IMG_CF_TRUE_COLOR,
    .data = g_nav_icon_rgb565,
};

/* ============================================================
 * Initialization
 * ============================================================ */
void hud_renderer_init(void)
{
    static bool ui_initialized = false;
    if (!ui_initialized) {
        ui_init();
        ui_initialized = true;
        ESP_LOGI(TAG, "LVGL UI initialized");
    }

    memset(&s_prev, 0, sizeof(s_prev));
    s_prev.last_hour = -1;
    s_prev.last_minute = -1;
    s_prev.last_second = -1;
    s_prev.last_day = -1;
    s_prev.last_month = -1;
    s_prev.last_year = -1;
    s_prev.last_wifi_state = false;
    s_prev.last_battery = -2;
    s_prev.last_temp = -9999;
    s_prev.last_notif_active = false;
    s_prev.initialized = true;
}

/* ============================================================
 * Header / Status Bar Updates (Common to all screens)
 * ============================================================ */
void hud_render_wifi_status(bool connected, const char *ip_address)
{
    hud_render_header(connected, ip_address, -1, -999);
}

void hud_render_header(bool connected, const char *ip_address, int battery, int temp)
{
    /* Only update if status fields changed */
    if (connected == s_prev.last_wifi_state &&
        battery == s_prev.last_battery &&
        temp == s_prev.last_temp &&
        s_prev.initialized) {
        return; /* No change */
    }

    /* Configure Wi-Fi status indicators */
    char wifi_text[40];
    if (connected) {
        if (ip_address && strlen(ip_address) > 0) {
            snprintf(wifi_text, sizeof(wifi_text), "%s", ip_address);
        } else {
            snprintf(wifi_text, sizeof(wifi_text), LV_SYMBOL_WIFI " KẾT NỐI");
        }
    } else {
        snprintf(wifi_text, sizeof(wifi_text), LV_SYMBOL_WARNING " NGOẠI TUYẾN");
    }
    lv_color_t wifi_color = connected ? lv_color_make(0, 230, 118) : lv_color_make(244, 67, 54);

    /* Configure Weather Temperature label */
    char temp_str[16];
    if (connected && temp != -999) {
        snprintf(temp_str, sizeof(temp_str), "%d°C", temp);
    } else {
        snprintf(temp_str, sizeof(temp_str), "--°C");
    }

    /* Configure Battery indicators */
    int bat_val = (connected && battery >= 0) ? battery : 0;
    char bat_str[16];
    if (connected && battery >= 0) {
        snprintf(bat_str, sizeof(bat_str), "%d%%", battery);
    } else {
        snprintf(bat_str, sizeof(bat_str), "--%%");
    }
    lv_color_t bat_indicator_color = (bat_val < 20) ? lv_color_make(244, 67, 54) : 
                                     ((bat_val < 50) ? lv_color_make(255, 145, 0) : lv_color_make(0, 230, 118));

    /* Header components across screens */
    lv_obj_t *wifi_icons[] = { ui_Wifi_Icon, ui_Wifi_Icon_Notif, ui_Wifi_Icon_Nav, ui_Wifi_Icon_Stream };
    lv_obj_t *temp_labels[] = { ui_Temp_Label, ui_Temp_Label_Notif, ui_Temp_Label_Nav, ui_Temp_Label_Stream };
    lv_obj_t *bat_bars[] = { ui_Bat_Bar, ui_Bat_Bar_Notif, ui_Bat_Bar_Nav, ui_Bat_Bar_Stream };
    lv_obj_t *bat_labels[] = { ui_Bat_Label, ui_Bat_Label_Notif, ui_Bat_Label_Nav, ui_Bat_Label_Stream };

    for (int i = 0; i < 4; i++) {
        if (wifi_icons[i]) {
            lv_obj_set_style_border_color(wifi_icons[i], wifi_color, 0);
            if (lv_obj_get_child_cnt(wifi_icons[i]) > 0) {
                lv_obj_t *line = lv_obj_get_child(wifi_icons[i], 0);
                lv_obj_set_style_bg_color(line, wifi_color, 0);
            }
        }
        if (temp_labels[i]) {
            lv_label_set_text(temp_labels[i], temp_str);
        }
        if (bat_bars[i]) {
            lv_bar_set_value(bat_bars[i], bat_val, LV_ANIM_OFF);
            lv_obj_set_style_bg_color(bat_bars[i], bat_indicator_color, LV_PART_INDICATOR);
        }
        if (bat_labels[i]) {
            lv_label_set_text(bat_labels[i], bat_str);
        }
    }

    s_prev.last_wifi_state = connected;
    s_prev.last_battery = battery;
    s_prev.last_temp = temp;
}

/* ============================================================
 * Time & Date Rendering
 * ============================================================ */
void hud_render_time(const hud_time_data_t *time_data)
{
    if (time_data == NULL || !time_data->valid) return;

    /* Keep colon visible (removed blinking animation) */
    if (time_data->second != s_prev.last_second) {
        if (ui_Colon_Label) {
            lv_obj_clear_flag(ui_Colon_Label, LV_OBJ_FLAG_HIDDEN);
        }
        s_prev.last_second = time_data->second;
    }

    /* Redraw hours and minutes if values changed */
    if (time_data->hour != s_prev.last_hour || time_data->minute != s_prev.last_minute) {
        if (ui_Hour_Label) {
            lv_label_set_text_fmt(ui_Hour_Label, "%02d", time_data->hour);
        }
        if (ui_Min_Label) {
            lv_label_set_text_fmt(ui_Min_Label, "%02d", time_data->minute);
        }
        s_prev.last_hour = time_data->hour;
        s_prev.last_minute = time_data->minute;
    }
}

void hud_render_date(const hud_time_data_t *time_data)
{
    if (time_data == NULL || !time_data->valid) return;

    if (time_data->day == s_prev.last_day &&
        time_data->month == s_prev.last_month &&
        time_data->year == s_prev.last_year) {
        return; /* Date unchanged */
    }

    int wd = time_data->weekday;
    if (wd < 0 || wd > 6) wd = 0;

    if (ui_Date_Label) {
        lv_label_set_text_fmt(ui_Date_Label, "%s, %02d/%02d/%04d",
                              weekday_names[wd], time_data->day, time_data->month, time_data->year);
    }

    s_prev.last_day = time_data->day;
    s_prev.last_month = time_data->month;
    s_prev.last_year = time_data->year;
}

/* ============================================================
 * Notification Screens
 * ============================================================ */
static const lv_img_dsc_t* get_img_icon(const char* icon_name);
static const char* get_icon_symbol(const char* icon_name);



static const lv_img_dsc_t* get_img_icon(const char* icon_name) {
    if (!icon_name) return NULL;
    char lower[32] = {0};
    snprintf(lower, sizeof(lower), "%s", icon_name);
    for (int i = 0; lower[i]; i++) {
        if (lower[i] >= 'A' && lower[i] <= 'Z') lower[i] += 'a' - 'A';
    }
    if (strstr(lower, "zalo")) return NULL; // Zalo has an empty black image in this build, fall back to envelope symbol
    if (strstr(lower, "chat")) return &icon_chat;
    if (strstr(lower, "face")) return &icon_facebook;
    if (strstr(lower, "mess")) return &icon_messenger;
    if (strstr(lower, "tele")) return &icon_telegram;
    if (strstr(lower, "you")) return &icon_youtube;
    return NULL;
}

static const char* get_icon_symbol(const char* icon_name) {
    if (!icon_name) return LV_SYMBOL_BELL;
    char lower[32] = {0};
    snprintf(lower, sizeof(lower), "%s", icon_name);
    for (int i = 0; lower[i]; i++) {
        if (lower[i] >= 'A' && lower[i] <= 'Z') {
            lower[i] += 'a' - 'A';
        }
    }
    
    if (strstr(lower, "zalo") || strstr(lower, "face") || strstr(lower, "mess") || strstr(lower, "chat") || strstr(lower, "mail") || strstr(lower, "gmail")) {
        return LV_SYMBOL_ENVELOPE;
    } else if (strstr(lower, "call") || strstr(lower, "phone") || strstr(lower, "dial")) {
        return LV_SYMBOL_BELL; // Fall back to bell symbol because LV_SYMBOL_CALL renders as black/unsupported in this font build
    } else if (strstr(lower, "map") || strstr(lower, "nav")) {
        return LV_SYMBOL_GPS;
    } else if (strstr(lower, "music") || strstr(lower, "spot") || strstr(lower, "zing")) {
        return LV_SYMBOL_AUDIO;
    } else if (strstr(lower, "warn") || strstr(lower, "alert")) {
        return LV_SYMBOL_WARNING;
    }
    return LV_SYMBOL_BELL;
}

void hud_render_notification(const hud_notification_t *notif)
{
    if (notif == NULL || !notif->active) {
        if (s_prev.last_notif_active) {
            hud_clear_notification_area();
        }
        return;
    }

    /* Update values if changed */
    if (strcmp(s_prev.last_notif_title, notif->title) != 0 ||
        strcmp(s_prev.last_notif_msg, notif->message) != 0 ||
        !s_prev.last_notif_active) {

        if (ui_Notif_Title) {
            lv_label_set_text(ui_Notif_Title, notif->title);
        }
        if (ui_Notif_Msg) {
            lv_label_set_text(ui_Notif_Msg, notif->message);
        }
        
        const lv_img_dsc_t* img_dsc = get_img_icon(notif->icon);
        if (img_dsc) {
            
        if (!s_Notif_Img_Icon && ui_Notif_Icon) {
            // Dynamically create image widget to survive SquareLine Studio exports
            s_Notif_Img_Icon = lv_img_create(lv_obj_get_parent(ui_Notif_Icon));
            bool is_small = lv_disp_get_hor_res(NULL) <= 160;
            lv_obj_align(s_Notif_Img_Icon, LV_ALIGN_TOP_LEFT, is_small ? 2 : 10, is_small ? 0 : 8);
            lv_obj_add_flag(s_Notif_Img_Icon, LV_OBJ_FLAG_HIDDEN);
            
            // Dynamically fix the Y position of the message to fit 32px icon perfectly
            if (ui_Notif_Msg) {
                lv_obj_align(ui_Notif_Msg, LV_ALIGN_TOP_LEFT, is_small ? 2 : 10, is_small ? 20 : 53);
            }
        }
        if (s_Notif_Img_Icon) {
                lv_img_set_src(s_Notif_Img_Icon, img_dsc);
                lv_obj_clear_flag(s_Notif_Img_Icon, LV_OBJ_FLAG_HIDDEN);
            }
            if (ui_Notif_Icon) {
                lv_obj_add_flag(ui_Notif_Icon, LV_OBJ_FLAG_HIDDEN);
            }
        } else {
            if (s_Notif_Img_Icon) {
                lv_obj_add_flag(s_Notif_Img_Icon, LV_OBJ_FLAG_HIDDEN);
            }
            if (ui_Notif_Icon) {
                lv_obj_clear_flag(ui_Notif_Icon, LV_OBJ_FLAG_HIDDEN);
                lv_label_set_text(ui_Notif_Icon, get_icon_symbol(notif->icon));
            }
        }

        s_prev.last_notif_active = true;
        snprintf(s_prev.last_notif_title, NOTIF_TITLE_MAX_LEN, "%s", notif->title);
        snprintf(s_prev.last_notif_msg, NOTIF_MESSAGE_MAX_LEN, "%s", notif->message);

        ESP_LOGI(TAG, "Notification loaded: [%s] %s - %s", notif->icon, notif->title, notif->message);
    }

    /* Transition screen if not already visible */
    if (lv_scr_act() != ui_Screen_Notif) {
        lv_scr_load(ui_Screen_Notif);
    }
}

void hud_clear_notification_area(void)
{
    s_prev.last_notif_active = false;
    memset(s_prev.last_notif_title, 0, sizeof(s_prev.last_notif_title));
    memset(s_prev.last_notif_msg, 0, sizeof(s_prev.last_notif_msg));

    /* Fallback to main standby screen */
    if (lv_scr_act() == ui_Screen_Notif) {
        lv_scr_load(ui_Screen_Main);
    }
}

/* ============================================================
 * Boot & Waiting Screens
 * ============================================================ */
void hud_render_boot_screen(const char *ssid)
{
    hud_renderer_init();

    if (lv_scr_act() != ui_Screen_Boot) {
        lv_scr_load(ui_Screen_Boot);
    }

    if (ui_Boot_Status) {
        lv_label_set_text(ui_Boot_Status, "Đang kết nối WiFi...");
    }

    if (ui_Boot_SSID && ssid) {
        lv_label_set_text_fmt(ui_Boot_SSID, "SSID: %.16s", ssid);
    }
}

void hud_render_waiting_screen(void)
{
    hud_renderer_init();

    if (lv_scr_act() != ui_Screen_Waiting) {
        lv_scr_load(ui_Screen_Waiting);
    }

    char ip_buf[16];
    char ssid_buf[33];
    wifi_manager_get_ip(ip_buf, sizeof(ip_buf));
    wifi_manager_get_ssid(ssid_buf, sizeof(ssid_buf));

    if (ui_Wait_Title) {
        lv_label_set_text_fmt(ui_Wait_Title, "SSID: %s\nIP: %s", ssid_buf, ip_buf);
    }
}

/* ============================================================
 * Navigation Screen
 * ============================================================ */
void hud_render_nav_screen(const hud_nav_data_t *nav, const hud_time_data_t *time_data)
{
    hud_renderer_init();

    /* Transition screen if not active */
    if (lv_scr_act() != ui_Screen_Nav) {
        lv_scr_load(ui_Screen_Nav);
    }

    /* Update status bar with time context */
    bool wifi_ok = (wifi_manager_get_state() == WIFI_STATE_CONNECTED_STA || wifi_manager_get_state() == WIFI_STATE_AP_MODE);
    char ip_buf[16];
    wifi_manager_get_ip(ip_buf, sizeof(ip_buf));
    int battery = (time_data && time_data->valid) ? time_data->battery : -1;
    int temp = (time_data && time_data->valid) ? time_data->temp : -999;
    hud_render_header(wifi_ok, ip_buf, battery, temp);

    /* Bind navigation properties */
    bool nav_active = nav && nav->active;
    int nav_dist = nav_active ? nav->distance : 0;
    int nav_turn = nav_active ? nav->turn_type : 0;
    const char *nav_street = nav_active ? nav->street : "";
    const char *nav_time_left = nav_active ? nav->time_left : "";
    const char *nav_dist_left = nav_active ? nav->distance_left : "";
    const char *nav_eta = nav_active ? nav->eta : "";

    bool changed = (nav_active != s_prev.last_nav_active) ||
                   (nav_dist != s_prev.last_nav_distance) ||
                   (nav_turn != s_prev.last_nav_turn_type) ||
                   (strcmp(nav_street, s_prev.last_nav_street) != 0) ||
                   (strcmp(nav_time_left, s_prev.last_nav_time_left) != 0) ||
                   (strcmp(nav_dist_left, s_prev.last_nav_distance_left) != 0) ||
                   (strcmp(nav_eta, s_prev.last_nav_eta) != 0) ||
                   (g_nav_icon_valid != s_prev.last_nav_icon_valid);

    if (!changed) return; /* Cache hit, skip updates */

    s_prev.last_nav_active = nav_active;
    s_prev.last_nav_distance = nav_dist;
    s_prev.last_nav_turn_type = nav_turn;
    s_prev.last_nav_icon_valid = g_nav_icon_valid;
    snprintf(s_prev.last_nav_street, sizeof(s_prev.last_nav_street), "%s", nav_street);
    snprintf(s_prev.last_nav_time_left, sizeof(s_prev.last_nav_time_left), "%s", nav_time_left);
    snprintf(s_prev.last_nav_distance_left, sizeof(s_prev.last_nav_distance_left), "%s", nav_dist_left);
    snprintf(s_prev.last_nav_eta, sizeof(s_prev.last_nav_eta), "%s", nav_eta);

    if (nav_active) {
        /* Update street name */
        if (ui_Nav_Street) {
            lv_label_set_text(ui_Nav_Street, nav_street);
        }

        /* Update trip info */
        if (ui_Nav_Time_Left) {
            lv_label_set_text(ui_Nav_Time_Left, nav_time_left);
        }
        if (ui_Nav_Distance_Left) {
            lv_label_set_text(ui_Nav_Distance_Left, nav_dist_left);
        }
        if (ui_Nav_ETA) {
            lv_label_set_text(ui_Nav_ETA, nav_eta);
        }

        /* Update turn arrow indicator or image */
        if (g_nav_icon_valid) {
            if (ui_Nav_Arrow_Icon) lv_obj_add_flag(ui_Nav_Arrow_Icon, LV_OBJ_FLAG_HIDDEN);
            if (ui_Nav_Turn_Img) {
                lv_obj_clear_flag(ui_Nav_Turn_Img, LV_OBJ_FLAG_HIDDEN);
                lv_img_set_src(ui_Nav_Turn_Img, &s_nav_img_dsc);
            }
        } else {
            if (ui_Nav_Turn_Img) lv_obj_add_flag(ui_Nav_Turn_Img, LV_OBJ_FLAG_HIDDEN);
            if (ui_Nav_Arrow_Icon) {
                lv_obj_clear_flag(ui_Nav_Arrow_Icon, LV_OBJ_FLAG_HIDDEN);
                switch (nav_turn) {
                    case 1:
                        lv_label_set_text(ui_Nav_Arrow_Icon, LV_SYMBOL_UP);
                        lv_obj_set_style_text_color(ui_Nav_Arrow_Icon, lv_color_make(0, 229, 255), 0); /* Cyan */
                        break;
                    case 2:
                        lv_label_set_text(ui_Nav_Arrow_Icon, LV_SYMBOL_LEFT);
                        lv_obj_set_style_text_color(ui_Nav_Arrow_Icon, lv_color_make(255, 145, 0), 0); /* Orange */
                        break;
                    case 3:
                        lv_label_set_text(ui_Nav_Arrow_Icon, LV_SYMBOL_RIGHT);
                        lv_obj_set_style_text_color(ui_Nav_Arrow_Icon, lv_color_make(255, 145, 0), 0); /* Orange */
                        break;
                    case 4:
                        lv_label_set_text(ui_Nav_Arrow_Icon, LV_SYMBOL_REFRESH);
                        lv_obj_set_style_text_color(ui_Nav_Arrow_Icon, lv_color_make(244, 67, 54), 0); /* Red */
                        break;
                    default:
                        lv_label_set_text(ui_Nav_Arrow_Icon, LV_SYMBOL_UP);
                        lv_obj_set_style_text_color(ui_Nav_Arrow_Icon, lv_color_make(0, 229, 255), 0);
                        break;
                }
            }
        }

        /* Update distance label */
        if (ui_Nav_Dist) {
            if (nav_dist >= 1000) {
                lv_label_set_text_fmt(ui_Nav_Dist, "%d.%d km", nav_dist / 1000, (nav_dist % 1000) / 100);
            } else {
                lv_label_set_text_fmt(ui_Nav_Dist, "%d m", nav_dist);
            }
        }

    } else {
        /* Inactive Nav fallback */
        if (ui_Nav_Street) lv_label_set_text(ui_Nav_Street, "Không có dữ liệu");
        if (ui_Nav_Time_Left) lv_label_set_text(ui_Nav_Time_Left, "");
        if (ui_Nav_Distance_Left) lv_label_set_text(ui_Nav_Distance_Left, "");
        if (ui_Nav_ETA) lv_label_set_text(ui_Nav_ETA, "");
        if (ui_Nav_Arrow_Icon) {
            lv_obj_clear_flag(ui_Nav_Arrow_Icon, LV_OBJ_FLAG_HIDDEN);
            lv_label_set_text(ui_Nav_Arrow_Icon, "↑");
        }
        if (ui_Nav_Turn_Img) lv_obj_add_flag(ui_Nav_Turn_Img, LV_OBJ_FLAG_HIDDEN);
        if (ui_Nav_Dist) lv_label_set_text(ui_Nav_Dist, "Mở Map...");
    }
}

/* ============================================================
 * Screen Stream Screen
 * ============================================================ */
void hud_render_stream_screen(bool first_time)
{
    hud_renderer_init();

    if (lv_scr_act() != ui_Screen_Stream) {
        lv_scr_load(ui_Screen_Stream);
    }

    if (first_time && ui_Stream_Msg) {
        lv_label_set_text(ui_Stream_Msg, "STREAM MÀN HÌNH\nĐang truyền hình ảnh...");
    }
}

void hud_render_map_nav_loading_screen(bool first_time)
{
    hud_renderer_init();

    if (lv_scr_act() != ui_Screen_Stream) {
        lv_scr_load(ui_Screen_Stream);
    }

    if (first_time && ui_Stream_Msg) {
        lv_label_set_text(ui_Stream_Msg, "BẢN ĐỒ DẪN ĐƯỜNG\nĐang truyền hình ảnh...");
    }
}



/* ============================================================
 * Full UI Update Cycle
 * ============================================================ */
void hud_update(void)
{
    static hud_mode_t s_last_mode = HUD_MODE_DEFAULT;
    bool wifi_ok = (wifi_manager_get_state() == WIFI_STATE_CONNECTED_STA || wifi_manager_get_state() == WIFI_STATE_AP_MODE);
    char ip_buf[16];
    wifi_manager_get_ip(ip_buf, sizeof(ip_buf));

    hud_time_data_t time_data;
    bool has_time = http_server_get_time(&time_data);
    int battery = has_time ? time_data.battery : -1;
    int temp = has_time ? time_data.temp : -999;

    hud_mode_t current_mode = http_server_get_mode();

    /* Check if we just exited a streaming/map mode to invalidate the screen for LVGL redraw */
    if ((s_last_mode == HUD_MODE_STREAM || s_last_mode == HUD_MODE_MAP_NAV) && 
        (current_mode != HUD_MODE_STREAM && current_mode != HUD_MODE_MAP_NAV)) {
        ESP_LOGI(TAG, "Exited stream/map mode, invalidating screen for LVGL redraw");
        hud_stream_reset_active();
        display_clear(COLOR_BLACK);
        lv_obj_invalidate(lv_scr_act());
    }

    if (current_mode == HUD_MODE_NAV) {
        hud_nav_data_t nav;
        bool has_http_nav = http_server_get_nav(&nav);
        
        hud_render_nav_screen(has_http_nav ? &nav : NULL, has_time ? &time_data : NULL);

        s_last_mode = HUD_MODE_NAV;
    } else if (current_mode == HUD_MODE_STREAM) {
        static bool s_stream_first = true;
        if (s_last_mode != HUD_MODE_STREAM) {
            s_stream_first = true;
            s_last_mode = HUD_MODE_STREAM;
        }
        hud_render_stream_screen(s_stream_first);
        s_stream_first = false;
    } else if (current_mode == HUD_MODE_MAP_NAV) {
        static bool s_map_first = true;
        if (s_last_mode != HUD_MODE_MAP_NAV) {
            s_map_first = true;
            s_last_mode = HUD_MODE_MAP_NAV;
        }
        hud_render_map_nav_loading_screen(s_map_first);
        s_map_first = false;
    } else {
        /* Default mode (Standby + notification handling) */
        s_last_mode = HUD_MODE_DEFAULT;

        /* Manage active notifications */
        bool current_notif_active = false;
        hud_notification_t notif;
        if (http_server_get_notification(&notif)) {
            int64_t now_ms = esp_timer_get_time() / 1000;
            int64_t timeout_ms = 30 * 1000LL; /* Auto-dismiss notifications after 30 seconds */
            if (notif.active && (now_ms - notif.timestamp > timeout_ms)) {
                http_server_clear_notification();
                current_notif_active = false;
            } else {
                current_notif_active = notif.active;
            }
        }

        if (current_notif_active) {
            hud_render_notification(&notif);
        } else {
            /* Standby main view */
            if (lv_scr_act() != ui_Screen_Main) {
                lv_scr_load(ui_Screen_Main);
            }
        }

        /* Update header elements */
        hud_render_header(wifi_ok, ip_buf, battery, temp);

        /* Update standby time and date labels */
        if (has_time) {
            hud_render_time(&time_data);
            hud_render_date(&time_data);
        }
    }
}
