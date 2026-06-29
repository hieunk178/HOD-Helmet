/*
 * hud_stream.c - HUD Screen Streaming Receiver & Renderer
 */

#include "hud_stream.h"
#include "display_driver.h"
#include "http_server.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "lwip/sockets.h"
#include "rom/tjpgd.h"
#include <string.h>

#define TAG "hud_stream"
#define UDP_PORT 5001
#define JPEG_MAX_SIZE 32768

// Double buffers
static uint8_t s_jpeg_buf_a[JPEG_MAX_SIZE];
static uint8_t s_jpeg_buf_b[JPEG_MAX_SIZE];

static uint8_t *volatile s_write_buf = s_jpeg_buf_a;
static uint8_t *volatile s_read_buf = s_jpeg_buf_b;

static volatile uint32_t s_write_size = 0;
static volatile uint32_t s_read_size = 0;

static volatile bool s_frame_ready = false;
static volatile bool s_write_frame_complete = false;
static volatile bool s_display_busy = false;

static uint16_t *s_frame_pixel_buf = NULL;

static hud_stream_config_t s_config = {
    .outW = 240,
    .outH = 240,
    .drawX = 0,
    .drawY = 0,
    .fps = 18,
    .quality = 50,
    .stream_id = 0,
    .active = false
};

static SemaphoreHandle_t s_config_mutex = NULL;
static SemaphoreHandle_t s_frame_sem = NULL;
static uint32_t s_last_packet_time = 0;

extern TaskHandle_t s_display_task_handle; // Defined in display_driver.c

// Stream Config Getter
void hud_stream_get_config(hud_stream_config_t *out_config) {
    if (s_config_mutex && xSemaphoreTake(s_config_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        *out_config = s_config;
        xSemaphoreGive(s_config_mutex);
    }
}

bool hud_stream_is_active(void) {
    bool active = false;
    if (s_config_mutex && xSemaphoreTake(s_config_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        uint32_t now = xTaskGetTickCount() * portTICK_PERIOD_MS;
        // If stream is active but we haven't received packets for 2 seconds, mark as inactive
        if (s_config.active && (now - s_last_packet_time > 2000)) {
            s_config.active = false;
        }
        active = s_config.active;
        xSemaphoreGive(s_config_mutex);
    }
    return active;
}

void hud_stream_reset_active(void) {
    if (s_config_mutex && xSemaphoreTake(s_config_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_config.active = false;
        xSemaphoreGive(s_config_mutex);
    }
}

// TJpgDec Input Stream Struct
typedef struct {
    const uint8_t *data;
    uint32_t size;
    uint32_t offset;
} jpeg_stream_t;

// Input callback for TJpgDec
static UINT jpeg_input_func(JDEC *jd, BYTE *buff, UINT nbyte) {
    jpeg_stream_t *stream = (jpeg_stream_t *)jd->device;
    uint32_t remaining = stream->size - stream->offset;
    uint32_t to_read = (nbyte < remaining) ? nbyte : remaining;
    if (to_read > 0) {
        if (buff) {
            memcpy(buff, stream->data + stream->offset, to_read);
        }
        stream->offset += to_read;
    }
    return to_read;
}

// Output callback for TJpgDec
// Converts RGB888 output to RGB565 and draws it directly to display
static UINT jpeg_output_func(JDEC *jd, void *bitmap, JRECT *rect) {
    uint16_t x = rect->left;
    uint16_t y = rect->top;
    uint16_t w = rect->right - rect->left + 1;
    uint16_t h = rect->bottom - rect->top + 1;
    
    // Allocate pixel buffer on stack (max block is 16x16 = 256 pixels = 512 bytes)
    uint16_t pixel_buf[256];
    if (w * h > 256) {
        ESP_LOGE(TAG, "MCU block size too large: %dx%d", w, h);
        return 0; // abort decompression
    }
    
    uint8_t *src = (uint8_t *)bitmap;
    int num_pixels = w * h;
    for (int i = 0; i < num_pixels; i++) {
        uint8_t r = src[i * 3 + 0];
        uint8_t g = src[i * 3 + 1];
        uint8_t b = src[i * 3 + 2];
        // Swapped R and B here because ST7789 in this config expects BGR565
        uint16_t color = ((b & 0xF8) << 8) | ((g & 0xFC) << 3) | (r >> 3);
        pixel_buf[i] = (color >> 8) | (color << 8);
    }
    
    // Get draw config
    hud_stream_config_t cfg;
    hud_stream_get_config(&cfg);
    
    if (s_frame_pixel_buf) {
        // Copy to full frame buffer
        for (int row = 0; row < h; row++) {
            memcpy(&s_frame_pixel_buf[(y + row) * cfg.outW + x], &pixel_buf[row * w], w * 2);
        }
    } else {
        // Fallback to direct draw (slower)
        display_draw_bitmap(cfg.drawX + x, cfg.drawY + y, w, h, pixel_buf);
    }
    
    return 1; // continue decompression
}

// Decode and render JPEG frame
static void decode_and_draw_jpeg(const uint8_t *jpeg_data, uint32_t size) {
    if (size == 0) return;
    
    #define WORK_BUF_SIZE 5120
    uint8_t *work_buf = malloc(WORK_BUF_SIZE);
    if (!work_buf) {
        ESP_LOGE(TAG, "Failed to allocate decoder work buffer");
        return;
    }
    
    jpeg_stream_t stream = {
        .data = jpeg_data,
        .size = size,
        .offset = 0
    };
    
    JDEC jd;
    JRESULT res = jd_prepare(&jd, jpeg_input_func, work_buf, WORK_BUF_SIZE, &stream);
    if (res != JDR_OK) {
        ESP_LOGE(TAG, "jd_prepare failed: %d", res);
        free(work_buf);
        return;
    }
    
    res = jd_decomp(&jd, jpeg_output_func, 0); // 0 = no scaling
    if (res != JDR_OK) {
        ESP_LOGE(TAG, "jd_decomp failed: %d", res);
    } else if (s_frame_pixel_buf) {
        // Only flush if decoding was successful and we are buffering
        hud_stream_config_t cfg;
        hud_stream_get_config(&cfg);
        display_draw_bitmap(cfg.drawX, cfg.drawY, cfg.outW, cfg.outH, s_frame_pixel_buf);
    }
    
    free(work_buf);
}

static void hud_stream_display_task(void *pvParameters) {
    ESP_LOGI(TAG, "Display task started");
    s_display_task_handle = xTaskGetCurrentTaskHandle();
    
    // Allocate max 240x240 frame buffer for fast SPI flush
    s_frame_pixel_buf = heap_caps_malloc(240 * 240 * 2, MALLOC_CAP_DEFAULT);
    if (!s_frame_pixel_buf) {
        ESP_LOGW(TAG, "Failed to allocate 115KB full frame buffer! FPS will be low.");
    } else {
        ESP_LOGI(TAG, "Allocated 115KB full frame buffer for fast rendering.");
    }
    
    while (1) {
        // Wait for a new frame signal
        if (xSemaphoreTake(s_frame_sem, portMAX_DELAY) == pdTRUE) {
            // Check if we have a frame completed or waiting
            if (!s_frame_ready && s_write_frame_complete) {
                // Swap write and read buffers
                uint8_t *temp = s_write_buf;
                s_write_buf = s_read_buf;
                s_read_buf = temp;
                s_read_size = s_write_size;
                s_frame_ready = true;
                s_write_frame_complete = false;
            }
            
            // If frame is ready, decode it
            if (s_frame_ready) {
                // Only decode if we are in stream mode!
                hud_mode_t current_mode = http_server_get_mode();
                if (current_mode == HUD_MODE_STREAM || current_mode == HUD_MODE_MAP_NAV) {
                    s_display_busy = true;
                    decode_and_draw_jpeg(s_read_buf, s_read_size);
                    s_display_busy = false;
                }
                s_frame_ready = false;
            }
        }
    }
}

static void hud_stream_rx_task(void *pvParameters) {
    ESP_LOGI(TAG, "UDP Rx task started, binding to port %d", UDP_PORT);
    
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    if (sock < 0) {
        ESP_LOGE(TAG, "Failed to create socket: %d", errno);
        vTaskDelete(NULL);
        return;
    }
    
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    server_addr.sin_port = htons(UDP_PORT);
    
    if (bind(sock, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0) {
        ESP_LOGE(TAG, "Failed to bind socket: %d", errno);
        close(sock);
        vTaskDelete(NULL);
        return;
    }
    
    // Increase socket receive buffer size to prevent dropping packets
    int rcv_buf_size = 32768;
    if (setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &rcv_buf_size, sizeof(rcv_buf_size)) < 0) {
        ESP_LOGW(TAG, "Failed to set socket receive buffer size: %d", errno);
    }
    
    // Allocate rx buffer (15 bytes header + max 1024 bytes payload)
    #define RX_BUF_SIZE 2048
    uint8_t rx_buf[RX_BUF_SIZE];
    
    uint16_t assembling_frame_id = 0xFFFF;
    uint16_t chunks_received = 0;
    bool chunk_received_flags[128];
    memset(chunk_received_flags, 0, sizeof(chunk_received_flags));
    
    while (1) {
        struct sockaddr_in source_addr;
        socklen_t socklen = sizeof(source_addr);
        int len = recvfrom(sock, rx_buf, sizeof(rx_buf) - 1, 0, (struct sockaddr *)&source_addr, &socklen);
        
        if (len < 15) {
            // Header is at least 15 bytes
            continue;
        }
        
        // Parse header
        uint8_t magic0 = rx_buf[0];
        uint8_t magic1 = rx_buf[1];
        if (magic0 != 0x53 || magic1 != 0x54) { // 'S', 'T'
            continue;
        }
        
        s_last_packet_time = xTaskGetTickCount() * portTICK_PERIOD_MS;
        
        uint8_t type = rx_buf[2];
        uint16_t stream_id = (rx_buf[3] << 8) | rx_buf[4];
        uint16_t frame_id = (rx_buf[5] << 8) | rx_buf[6];
        uint16_t chunk_id = (rx_buf[7] << 8) | rx_buf[8];
        uint16_t chunk_count = (rx_buf[9] << 8) | rx_buf[10];
        uint32_t total_size = ((uint32_t)rx_buf[11] << 24) | ((uint32_t)rx_buf[12] << 16) | ((uint32_t)rx_buf[13] << 8) | rx_buf[14];
        
        int payload_len = len - 15;
        uint8_t *payload = rx_buf + 15;
        
        if (type == 1) { // CONFIG Packet
            if (payload_len < 10) {
                continue;
            }
            uint16_t outW = (payload[0] << 8) | payload[1];
            uint16_t outH = (payload[2] << 8) | payload[3];
            uint16_t drawX = (payload[4] << 8) | payload[5];
            uint16_t drawY = (payload[6] << 8) | payload[7];
            uint8_t fps = payload[8];
            uint8_t quality = payload[9];
            
            ESP_LOGI(TAG, "Received CONFIG: streamId=%d, outW=%d, outH=%d, drawX=%d, drawY=%d, fps=%d, quality=%d",
                     stream_id, outW, outH, drawX, drawY, fps, quality);
            
            if (xSemaphoreTake(s_config_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                s_config.outW = outW;
                s_config.outH = outH;
                s_config.drawX = drawX;
                s_config.drawY = drawY;
                s_config.fps = fps;
                s_config.quality = quality;
                s_config.stream_id = stream_id;
                s_config.active = true;
                xSemaphoreGive(s_config_mutex);
            }
            
            // Clear screen once when CONFIG is received
            display_clear(COLOR_BLACK);
            // Reset frame assembling so we don't reject the new stream's frames!
            assembling_frame_id = 0xFFFF;
            continue;
        }
        
        if (type == 2) { // DATA / CHUNK Packet
            // If not in stream or map nav mode, discard
            hud_mode_t cur_mode = http_server_get_mode();
            if (cur_mode != HUD_MODE_STREAM && cur_mode != HUD_MODE_MAP_NAV) {
                continue;
            }
            
            // Frame reassembly logic
            // Check if this chunk is for a newer frame
            bool is_new_frame = false;
            if (assembling_frame_id == 0xFFFF) {
                is_new_frame = true;
            } else {
                int16_t diff = (int16_t)(frame_id - assembling_frame_id);
                if (diff > 0) {
                    is_new_frame = true;
                } else if (diff < 0) {
                    // Older frame, discard chunk
                    continue;
                }
            }
            
            if (is_new_frame) {
                assembling_frame_id = frame_id;
                chunks_received = 0;
                memset(chunk_received_flags, 0, sizeof(chunk_received_flags));
            }
            
            // Check chunk size and index bounds
            if (chunk_id >= 128 || chunk_id >= chunk_count) {
                continue;
            }
            
            if (!chunk_received_flags[chunk_id]) {
                // Calculate write offset (each chunk has 1000 bytes offset)
                uint32_t offset = chunk_id * 1000;
                if (offset + payload_len <= JPEG_MAX_SIZE) {
                    memcpy(s_write_buf + offset, payload, payload_len);
                    chunk_received_flags[chunk_id] = true;
                    chunks_received++;
                    
                    // If we got all chunks for this frame
                    if (chunks_received == chunk_count) {
                        s_write_size = total_size;
                        s_write_frame_complete = true;
                        xSemaphoreGive(s_frame_sem);
                    }
                }
            }
        }
    }
    
    close(sock);
    vTaskDelete(NULL);
}

void hud_stream_init(void) {
    if (s_config_mutex == NULL) {
        s_config_mutex = xSemaphoreCreateMutex();
    }
    if (s_frame_sem == NULL) {
        s_frame_sem = xSemaphoreCreateBinary();
    }
    
    // Create Receiver task with priority 6 (higher than LVGL task)
    xTaskCreate(hud_stream_rx_task, "hud_stream_rx", 8192, NULL, 6, NULL);
    
    // Create Display task with priority 5 (equal to LVGL task)
    xTaskCreate(hud_stream_display_task, "hud_stream_dsp", 8192, NULL, 5, NULL);
}
