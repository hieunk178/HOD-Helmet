package com.hudhelmet.controller.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hudhelmet.controller.model.ConnectionState
import com.hudhelmet.controller.model.EspStatus
import com.hudhelmet.controller.ui.theme.*

/**
 * Card showing ESP32 connection status with animated indicator.
 */
@Composable
fun ConnectionCard(
    connectionState: ConnectionState,
    ipAddress: String,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> StatusConnected
            ConnectionState.CONNECTING -> StatusConnecting
            ConnectionState.DISCONNECTED -> StatusDisconnected
            ConnectionState.ERROR -> HudRed
        },
        animationSpec = tween(500),
        label = "statusColor"
    )

    val glowRadius by animateDpAsState(
        targetValue = if (connectionState == ConnectionState.CONNECTED) 12.dp else 4.dp,
        animationSpec = tween(800),
        label = "glow"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = glowRadius,
                shape = RoundedCornerShape(16.dp),
                ambientColor = statusColor.copy(alpha = 0.3f),
                spotColor = statusColor.copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.4f),
                            statusColor.copy(alpha = 0.1f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Animated status dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "ESP32 HUD",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTED -> "Connected"
                                ConnectionState.CONNECTING -> "Connecting..."
                                ConnectionState.DISCONNECTED -> "Disconnected"
                                ConnectionState.ERROR -> "Connection Error"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                }

                IconButton(onClick = onReconnect) {
                    Icon(
                        imageVector = if (connectionState == ConnectionState.CONNECTED)
                            Icons.Default.Link else Icons.Default.Refresh,
                        contentDescription = "Reconnect",
                        tint = statusColor
                    )
                }
            }

            if (connectionState == ConnectionState.CONNECTED) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = DarkCardBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusChip(label = "IP", value = ipAddress)
                    StatusChip(
                        label = "Uptime",
                        value = "N/A"
                    )
                    StatusChip(
                        label = "Protocol",
                        value = "WebSocket"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = HudCyan
        )
    }
}

private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
