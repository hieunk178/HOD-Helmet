/*
 * ui.h - LVGL Heads-Up Display UI Interface
 */

#ifndef UI_H
#define UI_H

#ifdef __cplusplus
extern "C" {
#endif

#include "lvgl.h"

/* Screens */
extern lv_obj_t *ui_Screen_Boot;
extern lv_obj_t *ui_Screen_Waiting;
extern lv_obj_t *ui_Screen_Main;
extern lv_obj_t *ui_Screen_Notif;
extern lv_obj_t *ui_Screen_Nav;
extern lv_obj_t *ui_Screen_Stream;

/* Boot Screen Widgets */
extern lv_obj_t *ui_Boot_Status;
extern lv_obj_t *ui_Boot_SSID;

/* Waiting Screen Widgets */
extern lv_obj_t *ui_Wait_Title;

/* Main Standby Screen Widgets */
extern lv_obj_t *ui_Wifi_Icon;
extern lv_obj_t *ui_Temp_Label;
extern lv_obj_t *ui_Bat_Bar;
extern lv_obj_t *ui_Bat_Label;
extern lv_obj_t *ui_Hour_Label;
extern lv_obj_t *ui_Min_Label;
extern lv_obj_t *ui_Colon_Label;
extern lv_obj_t *ui_Date_Label;

/* Notif Screen Header Widgets */
extern lv_obj_t *ui_Wifi_Icon_Notif;
extern lv_obj_t *ui_Temp_Label_Notif;
extern lv_obj_t *ui_Bat_Bar_Notif;
extern lv_obj_t *ui_Bat_Label_Notif;

/* Nav Screen Header Widgets */
extern lv_obj_t *ui_Wifi_Icon_Nav;
extern lv_obj_t *ui_Temp_Label_Nav;
extern lv_obj_t *ui_Bat_Bar_Nav;
extern lv_obj_t *ui_Bat_Label_Nav;

/* Stream Screen Header Widgets */
extern lv_obj_t *ui_Wifi_Icon_Stream;
extern lv_obj_t *ui_Temp_Label_Stream;
extern lv_obj_t *ui_Bat_Bar_Stream;
extern lv_obj_t *ui_Bat_Label_Stream;


/* Notification Screen Widgets */
extern lv_obj_t *ui_Notif_Title;
extern lv_obj_t *ui_Notif_Msg;
extern lv_obj_t *ui_Notif_Icon;
extern lv_obj_t *ui_Notif_Img_Icon;

/* Navigation Screen Widgets */
extern lv_obj_t *ui_Nav_Arrow_Icon;
extern lv_obj_t *ui_Nav_Turn_Img;
extern lv_obj_t *ui_Nav_Dist;
extern lv_obj_t *ui_Nav_Street;
extern lv_obj_t *ui_Nav_Time_Left;
extern lv_obj_t *ui_Nav_Distance_Left;
extern lv_obj_t *ui_Nav_ETA;


/* Stream Screen Widgets */
extern lv_obj_t *ui_Stream_Msg;

/**
 * @brief Initialize all screens and components
 */
void ui_init(void);

#ifdef __cplusplus
} /*extern "C"*/
#endif

#endif /* UI_H */
