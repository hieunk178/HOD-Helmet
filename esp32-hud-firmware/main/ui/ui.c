/*
 * ui.c - LVGL Heads-Up Display UI Implementation
 */

#include "ui.h"
#include <stdio.h>
#include <stdlib.h>

LV_FONT_DECLARE(lv_font_vn_12);
LV_FONT_DECLARE(lv_font_vn_16);
LV_FONT_DECLARE(lv_font_vn_24);

/* Global screen pointers */
lv_obj_t *ui_Screen_Boot = NULL;
lv_obj_t *ui_Screen_Waiting = NULL;
lv_obj_t *ui_Screen_Main = NULL;
lv_obj_t *ui_Screen_Notif = NULL;
lv_obj_t *ui_Screen_Nav = NULL;
lv_obj_t *ui_Screen_Stream = NULL;

/* Boot Screen Widgets */
lv_obj_t *ui_Boot_Status = NULL;
lv_obj_t *ui_Boot_SSID = NULL;

/* Waiting Screen Widgets */
lv_obj_t *ui_Wait_Title = NULL;

/* Main Standby Screen Widgets */
lv_obj_t *ui_Wifi_Icon = NULL;
lv_obj_t *ui_Temp_Label = NULL;
lv_obj_t *ui_Bat_Bar = NULL;
lv_obj_t *ui_Bat_Label = NULL;
lv_obj_t *ui_Hour_Label = NULL;
lv_obj_t *ui_Min_Label = NULL;
lv_obj_t *ui_Colon_Label = NULL;
lv_obj_t *ui_Date_Label = NULL;

/* Notif Screen Header Widgets */
lv_obj_t *ui_Wifi_Icon_Notif = NULL;
lv_obj_t *ui_Temp_Label_Notif = NULL;
lv_obj_t *ui_Bat_Bar_Notif = NULL;
lv_obj_t *ui_Bat_Label_Notif = NULL;

/* Nav Screen Header Widgets */
lv_obj_t *ui_Wifi_Icon_Nav = NULL;
lv_obj_t *ui_Temp_Label_Nav = NULL;
lv_obj_t *ui_Bat_Bar_Nav = NULL;
lv_obj_t *ui_Bat_Label_Nav = NULL;

/* Stream Screen Header Widgets */
lv_obj_t *ui_Wifi_Icon_Stream = NULL;
lv_obj_t *ui_Temp_Label_Stream = NULL;
lv_obj_t *ui_Bat_Bar_Stream = NULL;
lv_obj_t *ui_Bat_Label_Stream = NULL;


/* Notification Screen Widgets */
lv_obj_t *ui_Notif_Title = NULL;
lv_obj_t *ui_Notif_Msg = NULL;
lv_obj_t *ui_Notif_Icon = NULL;
lv_obj_t *ui_Notif_Img_Icon = NULL;

/* Navigation Screen Widgets */
lv_obj_t *ui_Nav_Arrow_Icon = NULL;
lv_obj_t *ui_Nav_Turn_Img = NULL;
lv_obj_t *ui_Nav_Dist = NULL;
lv_obj_t *ui_Nav_Time_Left = NULL;
lv_obj_t *ui_Nav_Street = NULL;
lv_obj_t *ui_Nav_Distance_Left = NULL;
lv_obj_t *ui_Nav_ETA = NULL;
lv_obj_t *ui_Nav_Map_Panel = NULL;
lv_obj_t *ui_Nav_Car_Icon = NULL;
/* Stream Screen Widgets */
lv_obj_t *ui_Stream_Msg = NULL;

/* Global styles */
static lv_style_t style_screen;
static lv_style_t style_text_primary;
static lv_style_t style_text_secondary;
static lv_style_t style_text_accent;
static lv_style_t style_card;

static void init_styles(int is_small)
{
    /* Background screen style */
    lv_style_init(&style_screen);
    lv_style_set_bg_color(&style_screen, lv_color_black());
    lv_style_set_bg_opa(&style_screen, LV_OPA_COVER);
    lv_style_set_text_color(&style_screen, lv_color_white());

    /* Primary text style (White) */
    lv_style_init(&style_text_primary);
    lv_style_set_text_color(&style_text_primary, lv_color_white());
    lv_style_set_text_font(&style_text_primary, is_small ? &lv_font_vn_12 : &lv_font_vn_16);

    /* Secondary text style (Light Grey) */
    lv_style_init(&style_text_secondary);
    lv_style_set_text_color(&style_text_secondary, lv_color_make(180, 180, 180));
    lv_style_set_text_font(&style_text_secondary, is_small ? &lv_font_vn_12 : &lv_font_vn_16);

    /* Accent text style (Neon Cyan) */
    lv_style_init(&style_text_accent);
    lv_style_set_text_color(&style_text_accent, lv_color_make(0, 229, 255));
    lv_style_set_text_font(&style_text_accent, is_small ? &lv_font_vn_12 : &lv_font_vn_16);

    /* Card panel style */
    lv_style_init(&style_card);
    lv_style_set_bg_color(&style_card, lv_color_make(20, 20, 20));
    lv_style_set_bg_opa(&style_card, LV_OPA_COVER);
    lv_style_set_border_color(&style_card, lv_color_make(0, 229, 255));
    lv_style_set_border_width(&style_card, 1);
    lv_style_set_border_opa(&style_card, LV_OPA_80);
    lv_style_set_radius(&style_card, is_small ? 4 : 8);
    lv_style_set_pad_all(&style_card, is_small ? 4 : 8);
}

static void create_header(lv_obj_t *parent, int is_small, lv_obj_t **wifi, lv_obj_t **temp, lv_obj_t **bat_bar, lv_obj_t **bat_lbl)
{
    int h_height = is_small ? 14 : 32;
    int pad_lr = is_small ? 4 : 10;

    lv_obj_t *header = lv_obj_create(parent);
    lv_obj_set_size(header, lv_pct(100), h_height);
    lv_obj_align(header, LV_ALIGN_TOP_MID, 0, 0);
    lv_obj_set_style_bg_color(header, lv_color_make(10, 10, 10), 0);
    lv_obj_set_style_bg_opa(header, LV_OPA_COVER, 0);
    lv_obj_set_style_border_color(header, lv_color_make(30, 30, 30), 0);
    lv_obj_set_style_border_side(header, LV_BORDER_SIDE_BOTTOM, 0);
    lv_obj_set_style_border_width(header, 1, 0);
    lv_obj_set_style_pad_left(header, pad_lr, 0);
    lv_obj_set_style_pad_right(header, pad_lr, 0);
    lv_obj_set_style_pad_top(header, 0, 0);
    lv_obj_set_style_pad_bottom(header, 0, 0);
    lv_obj_clear_flag(header, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(header, LV_SCROLLBAR_MODE_OFF);

    /* Wi-Fi status symbol: Pill Container outline */
    *wifi = lv_obj_create(header);
    lv_obj_set_size(*wifi, is_small ? 14 : 24, is_small ? 8 : 12);
    lv_obj_align(*wifi, LV_ALIGN_LEFT_MID, 0, 0);
    lv_obj_set_style_radius(*wifi, is_small ? 4 : 6, 0);
    lv_obj_set_style_bg_opa(*wifi, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(*wifi, 1, 0);
    lv_obj_set_style_border_color(*wifi, lv_color_make(150, 150, 150), 0);
    lv_obj_set_style_pad_all(*wifi, 0, 0);
    lv_obj_clear_flag(*wifi, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(*wifi, LV_SCROLLBAR_MODE_OFF);

    /* Inner horizontal dash line */
    lv_obj_t *wifi_line = lv_obj_create(*wifi);
    lv_obj_set_size(wifi_line, is_small ? 6 : 12, is_small ? 1 : 2);
    lv_obj_align(wifi_line, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_bg_color(wifi_line, lv_color_make(150, 150, 150), 0);
    lv_obj_set_style_bg_opa(wifi_line, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(wifi_line, 0, 0);
    lv_obj_clear_flag(wifi_line, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(wifi_line, LV_SCROLLBAR_MODE_OFF);

    /* Temperature */
    *temp = lv_label_create(header);
    lv_obj_align(*temp, LV_ALIGN_CENTER, 0, 0);
    lv_label_set_text(*temp, "--°C");
    lv_obj_set_style_text_color(*temp, lv_color_make(0, 229, 255), 0);
    lv_obj_set_style_text_font(*temp, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);

    /* Battery container */
    lv_obj_t *bat_cont = lv_obj_create(header);
    lv_obj_set_size(bat_cont, is_small ? 45 : 80, lv_pct(100));
    lv_obj_align(bat_cont, LV_ALIGN_RIGHT_MID, 0, 0);
    lv_obj_set_style_bg_opa(bat_cont, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(bat_cont, 0, 0);
    lv_obj_set_style_pad_all(bat_cont, 0, 0);
    lv_obj_clear_flag(bat_cont, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(bat_cont, LV_SCROLLBAR_MODE_OFF);

    /* Battery level bar */
    *bat_bar = lv_bar_create(bat_cont);
    lv_obj_set_size(*bat_bar, is_small ? 14 : 26, is_small ? 6 : 10);
    lv_obj_align(*bat_bar, LV_ALIGN_RIGHT_MID, 0, 0);
    lv_bar_set_range(*bat_bar, 0, 100);
    lv_bar_set_value(*bat_bar, 50, LV_ANIM_OFF);
    lv_obj_set_style_bg_color(*bat_bar, lv_color_make(40, 40, 40), LV_PART_MAIN);
    lv_obj_set_style_bg_color(*bat_bar, lv_color_make(0, 230, 118), LV_PART_INDICATOR);

    /* Battery label */
    *bat_lbl = lv_label_create(bat_cont);
    lv_obj_align_to(*bat_lbl, *bat_bar, LV_ALIGN_OUT_LEFT_MID, is_small ? -2 : -5, 0);
    lv_label_set_text(*bat_lbl, "--%");
    lv_obj_set_style_text_color(*bat_lbl, lv_color_white(), 0);
    lv_obj_set_style_text_font(*bat_lbl, is_small ? &lv_font_vn_12 : &lv_font_vn_12, 0);
}

void ui_init(void)
{
    int w = lv_disp_get_hor_res(NULL);
    int is_small = (w <= 160);
    int card_width = is_small ? 152 : (w == 240 ? 220 : 300);

    init_styles(is_small);

    /* ============================================================
     * 1. BOOT SCREEN
     * ============================================================ */
    ui_Screen_Boot = lv_obj_create(NULL);
    lv_obj_add_style(ui_Screen_Boot, &style_screen, 0);
    lv_obj_clear_flag(ui_Screen_Boot, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(ui_Screen_Boot, LV_SCROLLBAR_MODE_OFF);

    /* Title */
    lv_obj_t *boot_title = lv_label_create(ui_Screen_Boot);
    lv_obj_align(boot_title, LV_ALIGN_TOP_MID, 0, is_small ? 6 : 24);
    lv_label_set_text(boot_title, "HUD HELMET");
    lv_obj_set_style_text_color(boot_title, lv_color_make(0, 229, 255), 0);
    lv_obj_set_style_text_font(boot_title, is_small ? &lv_font_vn_16 : &lv_font_montserrat_32, 0);

    /* Spinner */
    lv_obj_t *spinner = lv_spinner_create(ui_Screen_Boot, 1000, 60);
    lv_obj_set_size(spinner, is_small ? 22 : 48, is_small ? 22 : 48);
    lv_obj_align(spinner, LV_ALIGN_CENTER, 0, is_small ? 2 : 10);
    lv_obj_set_style_arc_color(spinner, lv_color_make(0, 229, 255), LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(spinner, lv_color_make(40, 40, 40), LV_PART_MAIN);
    lv_obj_set_style_arc_width(spinner, is_small ? 2 : 5, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(spinner, is_small ? 2 : 5, LV_PART_MAIN);

    /* Status connection message */
    ui_Boot_Status = lv_label_create(ui_Screen_Boot);
    lv_obj_align(ui_Boot_Status, LV_ALIGN_BOTTOM_MID, 0, is_small ? -16 : -55);
    lv_label_set_text(ui_Boot_Status, "Đang khởi động WiFi...");
    lv_obj_set_style_text_font(ui_Boot_Status, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);

    /* Connected SSID message */
    ui_Boot_SSID = lv_label_create(ui_Screen_Boot);
    lv_obj_align(ui_Boot_SSID, LV_ALIGN_BOTTOM_MID, 0, is_small ? -6 : -30);

    lv_label_set_text(ui_Boot_SSID, "");
    lv_obj_set_style_text_color(ui_Boot_SSID, lv_color_make(180, 180, 180), 0);
    lv_obj_set_style_text_font(ui_Boot_SSID, is_small ? &lv_font_vn_12 : &lv_font_vn_12, 0);

    /* Firmware version */
    lv_obj_t *boot_ver = lv_label_create(ui_Screen_Boot);
    lv_obj_align(boot_ver, LV_ALIGN_BOTTOM_MID, 0, is_small ? 0 : -8);
    lv_label_set_text(boot_ver, "v1.1.0");
    lv_obj_set_style_text_color(boot_ver, lv_color_make(90, 90, 90), 0);
    lv_obj_set_style_text_font(boot_ver, is_small ? &lv_font_vn_12 : &lv_font_vn_12, 0);


    /* ============================================================
     * 2. WAITING SCREEN
     * ============================================================ */
    ui_Screen_Waiting = lv_obj_create(NULL);
    lv_obj_add_style(ui_Screen_Waiting, &style_screen, 0);
    lv_obj_clear_flag(ui_Screen_Waiting, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(ui_Screen_Waiting, LV_SCROLLBAR_MODE_OFF);

    /* Connection Card Panel */
    lv_obj_t *wait_card = lv_obj_create(ui_Screen_Waiting);
    lv_obj_set_size(wait_card, is_small ? 144 : (w == 240 ? 220 : 260), is_small ? 54 : 120);
    lv_obj_align(wait_card, LV_ALIGN_CENTER, 0, 0);
    lv_obj_add_style(wait_card, &style_card, 0);
    lv_obj_clear_flag(wait_card, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(wait_card, LV_SCROLLBAR_MODE_OFF);

    /* IP address info */
    ui_Wait_Title = lv_label_create(wait_card);
    lv_obj_align(ui_Wait_Title, LV_ALIGN_TOP_MID, 0, is_small ? 2 : 12);
    lv_label_set_text(ui_Wait_Title, "IP: 192.168.4.1");
    lv_obj_set_style_text_color(ui_Wait_Title, lv_color_make(0, 229, 255), 0);
    lv_obj_set_style_text_font(ui_Wait_Title, is_small ? &lv_font_vn_12 : &lv_font_vn_24, 0);

    /* Waiting app text */
    lv_obj_t *wait_msg = lv_label_create(wait_card);
    lv_obj_align(wait_msg, LV_ALIGN_BOTTOM_MID, 0, is_small ? -4 : -15);
    lv_label_set_text(wait_msg, "Đang chờ ứng dụng kết nối...");
    lv_obj_set_style_text_font(wait_msg, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);


    /* ============================================================
     * 3. MAIN STANDBY SCREEN
     * ============================================================ */
    ui_Screen_Main = lv_obj_create(NULL);
    lv_obj_add_style(ui_Screen_Main, &style_screen, 0);
    lv_obj_clear_flag(ui_Screen_Main, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(ui_Screen_Main, LV_SCROLLBAR_MODE_OFF);

    /* Header Bar */
    create_header(ui_Screen_Main, is_small, &ui_Wifi_Icon, &ui_Temp_Label, &ui_Bat_Bar, &ui_Bat_Label);

    /* Clock Flex Box Layout Container Card */
    lv_obj_t *clock_cont = lv_obj_create(ui_Screen_Main);
    lv_obj_set_size(clock_cont, card_width, is_small ? 36 : 90);
    lv_obj_align(clock_cont, LV_ALIGN_CENTER, 0, is_small ? -4 : -10);
    lv_obj_set_style_bg_color(clock_cont, lv_color_make(10, 25, 30), 0); // Dark Blue-Gray background
    lv_obj_set_style_bg_opa(clock_cont, LV_OPA_COVER, 0);
    lv_obj_set_style_border_color(clock_cont, lv_color_make(0, 229, 255), 0); // Neon Cyan border
    lv_obj_set_style_border_width(clock_cont, 2, 0);
    lv_obj_set_style_radius(clock_cont, is_small ? 6 : 12, 0);
    lv_obj_set_style_pad_all(clock_cont, is_small ? 2 : 10, 0);
    lv_obj_set_flex_flow(clock_cont, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(clock_cont, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_clear_flag(clock_cont, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(clock_cont, LV_SCROLLBAR_MODE_OFF);

    /* Hours label */
    ui_Hour_Label = lv_label_create(clock_cont);
    lv_label_set_text(ui_Hour_Label, "12");
    lv_obj_set_style_text_color(ui_Hour_Label, lv_color_make(0, 229, 255), 0);
    lv_obj_set_style_text_font(ui_Hour_Label, is_small ? &lv_font_vn_24 : &lv_font_montserrat_48, 0);

    /* Colon */
    ui_Colon_Label = lv_label_create(clock_cont);
    lv_label_set_text(ui_Colon_Label, ":");
    lv_obj_set_style_text_color(ui_Colon_Label, lv_color_make(0, 229, 255), 0);
    lv_obj_set_style_text_font(ui_Colon_Label, is_small ? &lv_font_vn_24 : &lv_font_montserrat_48, 0);

    /* Minutes */
    ui_Min_Label = lv_label_create(clock_cont);
    lv_label_set_text(ui_Min_Label, "00");
    lv_obj_set_style_text_color(ui_Min_Label, lv_color_white(), 0);
    lv_obj_set_style_text_font(ui_Min_Label, is_small ? &lv_font_vn_24 : &lv_font_montserrat_48, 0);

    /* Date Label */
    ui_Date_Label = lv_label_create(ui_Screen_Main);
    lv_obj_align(ui_Date_Label, LV_ALIGN_CENTER, 0, is_small ? 20 : 45);
    lv_label_set_text(ui_Date_Label, "T2, 19/06/2026");
    lv_obj_set_style_text_color(ui_Date_Label, lv_color_make(180, 229, 255), 0); // Soft Ice-Blue color
    lv_obj_set_style_text_font(ui_Date_Label, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);

    /* Bottom active status bar badge (pill shape with green border) */
    lv_obj_t *status_badge = lv_obj_create(ui_Screen_Main);
    lv_obj_set_size(status_badge, is_small ? 90 : 140, is_small ? 16 : 24);
    lv_obj_align(status_badge, LV_ALIGN_BOTTOM_MID, 0, is_small ? 0 : -8);
    lv_obj_set_style_radius(status_badge, 10, 0);
    lv_obj_set_style_bg_opa(status_badge, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_color(status_badge, lv_color_make(0, 230, 118), 0); // Neon Green border
    lv_obj_set_style_border_width(status_badge, 1, 0);
    lv_obj_set_style_pad_all(status_badge, 0, 0);
    lv_obj_clear_flag(status_badge, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(status_badge, LV_SCROLLBAR_MODE_OFF);

    lv_obj_t *main_status = lv_label_create(status_badge);
    lv_obj_align(main_status, LV_ALIGN_CENTER, 0, 0);
    lv_label_set_text(main_status, "SYSTEM OK");
    lv_obj_set_style_text_color(main_status, lv_color_make(0, 230, 118), 0);
    lv_obj_set_style_text_font(main_status, is_small ? &lv_font_vn_12 : &lv_font_vn_12, 0);


    /* ============================================================
     * 4. NOTIFICATION SCREEN
     * ============================================================ */
    ui_Screen_Notif = lv_obj_create(NULL);
    lv_obj_add_style(ui_Screen_Notif, &style_screen, 0);
    lv_obj_clear_flag(ui_Screen_Notif, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(ui_Screen_Notif, LV_SCROLLBAR_MODE_OFF);

    /* Compact Header Bar */
    create_header(ui_Screen_Notif, is_small, &ui_Wifi_Icon_Notif, &ui_Temp_Label_Notif, &ui_Bat_Bar_Notif, &ui_Bat_Label_Notif);


    /* Notification rounded card container */
    lv_obj_t *notif_card = lv_obj_create(ui_Screen_Notif);
    lv_obj_set_size(notif_card, card_width, is_small ? 58 : 180);
    lv_obj_align(notif_card, LV_ALIGN_BOTTOM_MID, 0, is_small ? -2 : -10);
    lv_obj_add_style(notif_card, &style_card, 0);
    lv_obj_set_style_border_color(notif_card, lv_color_make(255, 145, 0), 0); /* Amber warning border */
    lv_obj_clear_flag(notif_card, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(notif_card, LV_SCROLLBAR_MODE_OFF);

    /* App sender/source icon (Image) */
    ui_Notif_Img_Icon = lv_img_create(notif_card);
    lv_obj_align(ui_Notif_Img_Icon, LV_ALIGN_TOP_LEFT, is_small ? 2 : 10, is_small ? 0 : 8);
    lv_obj_add_flag(ui_Notif_Img_Icon, LV_OBJ_FLAG_HIDDEN);

    /* App sender/source icon (Label) */
    ui_Notif_Icon = lv_label_create(notif_card);
    lv_obj_align(ui_Notif_Icon, LV_ALIGN_TOP_LEFT, is_small ? 2 : 10, is_small ? 0 : 8);
    lv_label_set_text(ui_Notif_Icon, LV_SYMBOL_BELL);
    lv_obj_set_style_text_color(ui_Notif_Icon, lv_color_make(255, 145, 0), 0);
    lv_obj_set_style_text_font(ui_Notif_Icon, is_small ? &lv_font_vn_12 : &lv_font_vn_24, 0);

    /* App sender/source title */
    ui_Notif_Title = lv_label_create(notif_card);
    lv_obj_align(ui_Notif_Title, LV_ALIGN_TOP_LEFT, is_small ? 18 : 42, is_small ? 0 : 8);
    lv_label_set_text(ui_Notif_Title, "Zalo");
    lv_obj_set_style_text_color(ui_Notif_Title, lv_color_make(255, 145, 0), 0);
    lv_obj_set_style_text_font(ui_Notif_Title, is_small ? &lv_font_vn_12 : &lv_font_vn_24, 0);

    /* Notification message body */
    ui_Notif_Msg = lv_label_create(notif_card);
    lv_obj_set_width(ui_Notif_Msg, lv_pct(94));
    lv_obj_align(ui_Notif_Msg, LV_ALIGN_TOP_LEFT, is_small ? 2 : 10, is_small ? 20 : 53);
    lv_label_set_long_mode(ui_Notif_Msg, LV_LABEL_LONG_WRAP);
    lv_label_set_text(ui_Notif_Msg, "Bạn có tin nhắn mới từ Nguyễn Văn A...");
    lv_obj_set_style_text_color(ui_Notif_Msg, lv_color_white(), 0);
    lv_obj_set_style_text_font(ui_Notif_Msg, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);


    /* ============================================================
     * 5. NAVIGATION SCREEN
     * ============================================================ */
    ui_Screen_Nav = lv_obj_create(NULL);
    lv_obj_add_style(ui_Screen_Nav, &style_screen, 0);
    lv_obj_clear_flag(ui_Screen_Nav, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(ui_Screen_Nav, LV_SCROLLBAR_MODE_OFF);

    /* Navigation Header Bar */
    create_header(ui_Screen_Nav, is_small, &ui_Wifi_Icon_Nav, &ui_Temp_Label_Nav, &ui_Bat_Bar_Nav, &ui_Bat_Label_Nav);


    /* Main Area layout */
    lv_obj_t *nav_content = lv_obj_create(ui_Screen_Nav);
    lv_obj_set_size(nav_content, lv_pct(100), is_small ? 66 : 208);
    lv_obj_align(nav_content, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_obj_set_style_bg_opa(nav_content, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(nav_content, 0, 0);
    lv_obj_set_style_pad_all(nav_content, 0, 0);
    lv_obj_clear_flag(nav_content, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(nav_content, LV_SCROLLBAR_MODE_OFF);

    /* Main container for Navigation Data */
    lv_obj_t *nav_top = lv_obj_create(nav_content);
    lv_obj_set_size(nav_top, lv_pct(100), lv_pct(100));
    lv_obj_align(nav_top, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_bg_color(nav_top, lv_color_black(), 0);
    lv_obj_set_style_bg_opa(nav_top, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(nav_top, 0, 0);
    lv_obj_set_style_pad_all(nav_top, 0, 0);
    lv_obj_clear_flag(nav_top, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(nav_top, LV_SCROLLBAR_MODE_OFF);

    /* Turn Symbol (Large, centered horizontally, near top) */
    ui_Nav_Arrow_Icon = lv_label_create(nav_top);
    lv_obj_align(ui_Nav_Arrow_Icon, LV_ALIGN_TOP_MID, 0, is_small ? 2 : 10);
    lv_label_set_text(ui_Nav_Arrow_Icon, "↑");
    lv_obj_set_style_text_color(ui_Nav_Arrow_Icon, lv_color_make(0, 229, 255), 0);
    lv_obj_set_style_text_font(ui_Nav_Arrow_Icon, is_small ? &lv_font_montserrat_48 : &lv_font_montserrat_48, 0);

    /* Turn Image (Used when receiving bitmap from Android) */
    ui_Nav_Turn_Img = lv_img_create(nav_top);
    lv_obj_align(ui_Nav_Turn_Img, LV_ALIGN_TOP_MID, 0, is_small ? 15 : 30);
    lv_obj_add_flag(ui_Nav_Turn_Img, LV_OBJ_FLAG_HIDDEN); // Hidden by default

    /* Distance info (Large, centered below arrow) */
    ui_Nav_Dist = lv_label_create(nav_top);
    lv_obj_align(ui_Nav_Dist, LV_ALIGN_CENTER, 0, is_small ? 6 : 20);
    lv_label_set_text(ui_Nav_Dist, "450 m");
    lv_obj_set_style_text_color(ui_Nav_Dist, lv_color_white(), 0);
    lv_obj_set_style_text_font(ui_Nav_Dist, is_small ? &lv_font_vn_16 : &lv_font_vn_24, 0);

    /* Target Street Name label (Centered at bottom) */
    ui_Nav_Street = lv_label_create(nav_top);
    lv_obj_set_width(ui_Nav_Street, lv_pct(90));
    lv_label_set_long_mode(ui_Nav_Street, LV_LABEL_LONG_SCROLL_CIRCULAR);
    lv_obj_align(ui_Nav_Street, LV_ALIGN_BOTTOM_MID, 0, is_small ? -15 : -25);
    lv_label_set_text(ui_Nav_Street, "Nguyễn Trãi");
    lv_obj_set_style_text_color(ui_Nav_Street, lv_color_make(180, 180, 180), 0);
    lv_obj_set_style_text_font(ui_Nav_Street, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);
    lv_obj_set_style_text_align(ui_Nav_Street, LV_TEXT_ALIGN_CENTER, 0);

    /* Time Left label (Bottom, below street name) */
    ui_Nav_Time_Left = lv_label_create(nav_top);
    lv_obj_align(ui_Nav_Time_Left, LV_ALIGN_BOTTOM_MID, 0, is_small ? -2 : -5);
    lv_label_set_text(ui_Nav_Time_Left, "");
    lv_obj_set_style_text_color(ui_Nav_Time_Left, lv_color_make(120, 200, 120), 0);
    lv_obj_set_style_text_font(ui_Nav_Time_Left, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);

    /* Distance Left label (Top Left corner) */
    ui_Nav_Distance_Left = lv_label_create(nav_top);
    lv_obj_align(ui_Nav_Distance_Left, LV_ALIGN_TOP_LEFT, is_small ? 2 : 5, is_small ? 2 : 5);
    lv_label_set_text(ui_Nav_Distance_Left, "");
    lv_obj_set_style_text_color(ui_Nav_Distance_Left, lv_color_make(255, 200, 50), 0);
    lv_obj_set_style_text_font(ui_Nav_Distance_Left, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);

    /* ETA label (Top Right corner) */
    ui_Nav_ETA = lv_label_create(nav_top);
    lv_obj_align(ui_Nav_ETA, LV_ALIGN_TOP_RIGHT, is_small ? -2 : -5, is_small ? 2 : 5);
    lv_label_set_text(ui_Nav_ETA, "");
    lv_obj_set_style_text_color(ui_Nav_ETA, lv_color_make(200, 200, 200), 0);
    lv_obj_set_style_text_font(ui_Nav_ETA, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);


    /* ============================================================
     * 6. STREAM SCREEN
     * ============================================================ */
    ui_Screen_Stream = lv_obj_create(NULL);
    lv_obj_add_style(ui_Screen_Stream, &style_screen, 0);
    lv_obj_clear_flag(ui_Screen_Stream, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(ui_Screen_Stream, LV_SCROLLBAR_MODE_OFF);

    /* Header Bar */
    create_header(ui_Screen_Stream, is_small, &ui_Wifi_Icon_Stream, &ui_Temp_Label_Stream, &ui_Bat_Bar_Stream, &ui_Bat_Label_Stream);


    /* Streaming Symbol Icon */
    lv_obj_t *stream_ico = lv_label_create(ui_Screen_Stream);
    lv_obj_align(stream_ico, LV_ALIGN_CENTER, 0, is_small ? -12 : -30);
    lv_label_set_text(stream_ico, LV_SYMBOL_VIDEO);
    lv_obj_set_style_text_color(stream_ico, lv_color_make(0, 229, 255), 0);
    lv_obj_set_style_text_font(stream_ico, is_small ? &lv_font_vn_24 : &lv_font_montserrat_48, 0);

    /* Streaming message label description */
    ui_Stream_Msg = lv_label_create(ui_Screen_Stream);
    lv_obj_align(ui_Stream_Msg, LV_ALIGN_CENTER, 0, is_small ? 12 : 30);
    lv_label_set_text(ui_Stream_Msg, "STREAM MÀN HÌNH\nĐang chờ dữ liệu...");
    lv_obj_set_style_text_align(ui_Stream_Msg, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_font(ui_Stream_Msg, is_small ? &lv_font_vn_12 : &lv_font_vn_16, 0);
}
