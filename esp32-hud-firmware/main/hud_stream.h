/*
 * hud_stream.h - HUD Screen Streaming Receiver & Renderer
 */

#ifndef HUD_STREAM_H
#define HUD_STREAM_H

#include <stdint.h>
#include <stdbool.h>

/* Stream Config Structure */
typedef struct {
    uint16_t outW;
    uint16_t outH;
    uint16_t drawX;
    uint16_t drawY;
    uint8_t fps;
    uint8_t quality;
    uint16_t stream_id;
    bool active;
} hud_stream_config_t;

/**
 * @brief Initialize the HUD screen streaming receiver and decoder tasks.
 */
void hud_stream_init(void);

/**
 * @brief Get the current stream configuration.
 */
void hud_stream_get_config(hud_stream_config_t *out_config);
bool hud_stream_is_active(void);
void hud_stream_reset_active(void);

#endif /* HUD_STREAM_H */
