package com.hudhelmet.controller.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hudhelmet.controller.service.ScreenCaptureService
import com.hudhelmet.controller.ui.theme.*
import com.hudhelmet.controller.viewmodel.HudViewModel

@Composable
fun StreamScreen(
    viewModel: HudViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Get screen metrics
    val displayMetrics = context.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val screenHeight = displayMetrics.heightPixels
    val aspectRatio = screenWidth.toFloat() / screenHeight.toFloat()

    // Streaming state collected reactively from foreground service
    val isStreaming by ScreenCaptureService.isRunningFlow.collectAsState()

    val sharedPrefs = context.getSharedPreferences("hud_prefs", android.content.Context.MODE_PRIVATE)

    // Crop settings (percentages 0.0 to 1.0)
    var cropXPercent by remember { mutableStateOf(sharedPrefs.getFloat("crop_x_percent", 0f)) }
    var cropYPercent by remember { mutableStateOf(sharedPrefs.getFloat("crop_y_percent", 0f)) }
    var cropWPercent by remember { mutableStateOf(sharedPrefs.getFloat("crop_w_percent", 1f)) }
    var cropHPercent by remember { mutableStateOf(sharedPrefs.getFloat("crop_h_percent", 1f)) }

    // Coerce crop box dimensions so they stay inside the screen boundaries
    val maxCropW = 1f - cropXPercent
    val finalCropWPercent = cropWPercent.coerceAtMost(maxCropW).coerceAtLeast(0.1f)
    val maxCropH = 1f - cropYPercent
    val finalCropHPercent = cropHPercent.coerceAtMost(maxCropH).coerceAtLeast(0.1f)

    // Convert to pixel dimensions
    val cropX = (cropXPercent * screenWidth).toInt()
    val cropY = (cropYPercent * screenHeight).toInt()
    val cropW = (finalCropWPercent * screenWidth).toInt()
    val cropH = (finalCropHPercent * screenHeight).toInt()

    // Calculate scaling parameters (max 240x240)
    val scale = Math.min(240f / cropW, 240f / cropH)
    val outW = (cropW * scale).toInt().coerceIn(1, 240)
    val outH = (cropH * scale).toInt().coerceIn(1, 240)

    // Calculate drawing position (centered on 240x240 display)
    val drawX = (240 - outW) / 2
    val drawY = (240 - outH) / 2

    // Stream Quality and FPS
    var fps by remember { mutableStateOf(sharedPrefs.getInt("stream_fps", 18)) }
    var quality by remember { mutableStateOf(sharedPrefs.getInt("stream_quality", 50)) }

    LaunchedEffect(cropXPercent, cropYPercent, cropWPercent, cropHPercent, fps, quality) {
        sharedPrefs.edit()
            .putFloat("crop_x_percent", cropXPercent)
            .putFloat("crop_y_percent", cropYPercent)
            .putFloat("crop_w_percent", cropWPercent)
            .putFloat("crop_h_percent", cropHPercent)
            .putInt("stream_fps", fps)
            .putInt("stream_quality", quality)
            .apply()
    }

    // Register Media Projection Launcher
    val streamLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val startIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_CROP_X, cropX)
                putExtra(ScreenCaptureService.EXTRA_CROP_Y, cropY)
                putExtra(ScreenCaptureService.EXTRA_CROP_W, cropW)
                putExtra(ScreenCaptureService.EXTRA_CROP_H, cropH)
                putExtra(ScreenCaptureService.EXTRA_OUT_W, outW)
                putExtra(ScreenCaptureService.EXTRA_OUT_H, outH)
                putExtra(ScreenCaptureService.EXTRA_DRAW_X, drawX)
                putExtra(ScreenCaptureService.EXTRA_DRAW_Y, drawY)
                putExtra(ScreenCaptureService.EXTRA_FPS, fps)
                putExtra(ScreenCaptureService.EXTRA_QUALITY, quality)
                putExtra(ScreenCaptureService.EXTRA_ESP_IP, viewModel.espIpAddress.value)
            }
            ContextCompat.startForegroundService(context, startIntent)
            viewModel.setHudMode(3) // Switch ESP32 to Stream Mode
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Center visual icon for Cast
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = if (isStreaming) HudYellow.copy(alpha = 0.1f) else DarkSurface,
                    shape = RoundedCornerShape(40.dp)
                )
                .border(
                    width = 2.dp,
                    color = if (isStreaming) HudYellow else DarkCardBorder,
                    shape = RoundedCornerShape(40.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isStreaming) Icons.Default.CastConnected else Icons.Default.Cast,
                contentDescription = null,
                tint = if (isStreaming) HudYellow else TextSecondary,
                modifier = Modifier.size(36.dp)
            )
        }

        Text(
            text = if (isStreaming) "Đang Live Stream Màn Hình" else "Chưa Bắt Đầu Stream",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        // Live visual selection canvas
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = DarkCardBorder,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Vùng chụp màn hình (Preview)",
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )

                // Visual Representation of Phone Screen and Crop Region
                Box(
                    modifier = Modifier
                        .height(180.dp)
                        .aspectRatio(aspectRatio)
                        .background(DarkBackground)
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasW = size.width
                        val canvasH = size.height

                        val left = cropXPercent * canvasW
                        val top = cropYPercent * canvasH
                        val width = finalCropWPercent * canvasW
                        val height = finalCropHPercent * canvasH

                        // Draw background mask (dimmed area outside crop)
                        // Top mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.5f),
                            topLeft = Offset(0f, 0f),
                            size = Size(canvasW, top)
                        )
                        // Bottom mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.5f),
                            topLeft = Offset(0f, top + height),
                            size = Size(canvasW, canvasH - (top + height))
                        )
                        // Left mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.5f),
                            topLeft = Offset(0f, top),
                            size = Size(left, height)
                        )
                        // Right mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.5f),
                            topLeft = Offset(left + width, top),
                            size = Size(canvasW - (left + width), height)
                        )

                        // Draw crop rectangle borders
                        drawRect(
                            color = HudYellow,
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )
                        )
                    }
                }

                // Math Info Panel
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "Kích thước chụp: ${cropW}x${cropH} px (Vị trí: ${cropX}, ${cropY})", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(text = "Đầu ra LCD HUD: ${outW}x${outH} px (Vị trí vẽ: ${drawX}, ${drawY})", color = HudYellow, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(text = "Tỷ lệ Scale: ${String.format("%.2f", scale)}", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = DarkCardBorder,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Điều Chỉnh Vùng Crop",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // Slider Crop X
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Crop X (Trái)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(text = "${(cropXPercent * 100).toInt()}%", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = cropXPercent,
                        onValueChange = { cropXPercent = it },
                        valueRange = 0f..0.9f,
                        colors = SliderDefaults.colors(thumbColor = HudYellow, activeTrackColor = HudYellow)
                    )
                }

                // Slider Crop Y
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Crop Y (Trên)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(text = "${(cropYPercent * 100).toInt()}%", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = cropYPercent,
                        onValueChange = { cropYPercent = it },
                        valueRange = 0f..0.9f,
                        colors = SliderDefaults.colors(thumbColor = HudYellow, activeTrackColor = HudYellow)
                    )
                }

                // Slider Crop Width
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Chiều Rộng (W)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(text = "${(finalCropWPercent * 100).toInt()}%", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = cropWPercent,
                        onValueChange = { cropWPercent = it },
                        valueRange = 0.1f..1f,
                        colors = SliderDefaults.colors(thumbColor = HudYellow, activeTrackColor = HudYellow)
                    )
                }

                // Slider Crop Height
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Chiều Cao (H)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(text = "${(finalCropHPercent * 100).toInt()}%", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = cropHPercent,
                        onValueChange = { cropHPercent = it },
                        valueRange = 0.1f..1f,
                        colors = SliderDefaults.colors(thumbColor = HudYellow, activeTrackColor = HudYellow)
                    )
                }

                Divider(color = DarkCardBorder)

                Text(
                    text = "Tham Số Truyền Dẫn",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // Slider FPS
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "FPS (Tốc độ khung hình)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(text = "$fps FPS", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = fps.toFloat(),
                        onValueChange = { fps = it.toInt() },
                        valueRange = 5f..30f,
                        steps = 25,
                        colors = SliderDefaults.colors(thumbColor = HudYellow, activeTrackColor = HudYellow)
                    )
                }

                // Slider Quality
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Chất Lượng JPEG (Quality)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(text = "$quality%", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { quality = it.toInt() },
                        valueRange = 10f..100f,
                        steps = 90,
                        colors = SliderDefaults.colors(thumbColor = HudYellow, activeTrackColor = HudYellow)
                    )
                }

                // Action button to start/stop
                Button(
                    onClick = {
                        if (isStreaming) {
                            val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
                                action = ScreenCaptureService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                        } else {
                            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            streamLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isStreaming) MaterialTheme.colorScheme.error else HudYellow,
                        contentColor = if (isStreaming) TextPrimary else DarkBackground
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isStreaming) "Dừng Stream" else "Bắt Đầu Stream",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Informative Tip Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = HudYellow,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Lưu ý về hiệu năng",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Vùng crop sẽ được scale giữ tỉ lệ để không vượt quá 240x240 và truyền qua UDP giúp tối ưu băng thông. Hãy đảm bảo WiFi hoạt động ổn định và pin điện thoại đủ dùng.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}
