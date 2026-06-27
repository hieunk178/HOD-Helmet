import sys

file_path = 'c:/Project/HOD Helmet/esp32-hud-firmware/main/http_server.c'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

ws_logic = '''                    } else if (strcmp(cmd->valuestring, "wifi_delete") == 0) {
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
                            if (cJSON_IsString(title) && title->valuestring[0] != '\\0') {
                                strncpy(s_notification.title, title->valuestring, NOTIF_TITLE_MAX_LEN - 1);
                            } else if (cJSON_IsString(app) && app->valuestring[0] != '\\0') {
                                strncpy(s_notification.title, app->valuestring, NOTIF_TITLE_MAX_LEN - 1);
                            } else {
                                strncpy(s_notification.title, "Thông báo", NOTIF_TITLE_MAX_LEN - 1);
                            }
                            s_notification.title[NOTIF_TITLE_MAX_LEN - 1] = '\\0';
                            if (cJSON_IsString(message)) {
                                strncpy(s_notification.message, message->valuestring, NOTIF_MESSAGE_MAX_LEN - 1);
                            } else {
                                s_notification.message[0] = '\\0';
                            }
                            s_notification.message[NOTIF_MESSAGE_MAX_LEN - 1] = '\\0';
                            if (cJSON_IsString(icon) && icon->valuestring[0] != '\\0') {
                                strncpy(s_notification.icon, icon->valuestring, NOTIF_ICON_MAX_LEN - 1);
                            } else if (cJSON_IsString(app) && app->valuestring[0] != '\\0') {
                                strncpy(s_notification.icon, app->valuestring, NOTIF_ICON_MAX_LEN - 1);
                            } else {
                                strcpy(s_notification.icon, "bell");
                            }
                            s_notification.icon[NOTIF_ICON_MAX_LEN - 1] = '\\0';
                            s_notification.active = true;
                            s_notification.timestamp = esp_timer_get_time() / 1000;
                            xSemaphoreGive(s_data_mutex);
                        }
                    } else if (strcmp(cmd->valuestring, "clear_notif") == 0) {
                        http_server_clear_notification();
                    } else if (strcmp(cmd->valuestring, "mode") == 0) {
                        cJSON *mode = cJSON_GetObjectItem(root, "mode");
                        if (cJSON_IsNumber(mode) && mode->valueint >= 1 && mode->valueint <= 3) {
                            http_server_update_mode((hud_mode_t)mode->valueint);
                        }
                    } else if (strcmp(cmd->valuestring, "nav") == 0) {
                        cJSON *street = cJSON_GetObjectItem(root, "street");
                        cJSON *instruction = cJSON_GetObjectItem(root, "instruction");
                        cJSON *distance = cJSON_GetObjectItem(root, "distance");
                        cJSON *turn_type = cJSON_GetObjectItem(root, "turn_type");
                        cJSON *active = cJSON_GetObjectItem(root, "active");
                        if (cJSON_IsString(street) && cJSON_IsString(instruction) &&
                            cJSON_IsNumber(distance) && cJSON_IsNumber(turn_type)) {
                            hud_nav_data_t nav = {0};
                            strncpy(nav.street, street->valuestring, sizeof(nav.street) - 1);
                            strncpy(nav.instruction, instruction->valuestring, sizeof(nav.instruction) - 1);
                            nav.distance = distance->valueint;
                            nav.turn_type = turn_type->valueint;
                            nav.active = cJSON_IsBool(active) ? cJSON_IsTrue(active) : true;
                            nav.timestamp = esp_timer_get_time() / 1000;
                            http_server_update_nav(&nav);
                        }
                    }'''

old_logic = '''                    } else if (strcmp(cmd->valuestring, "wifi_delete") == 0) {
                        cJSON *ssid = cJSON_GetObjectItem(root, "ssid");
                        if (cJSON_IsString(ssid)) {
                            wifi_manager_delete_network(ssid->valuestring);
                            send_saved_networks(req); // send updated list
                        }
                    }'''

if old_logic in content:
    content = content.replace(old_logic, ws_logic)
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Successfully updated http_server.c")
else:
    print("Could not find old logic in http_server.c")
