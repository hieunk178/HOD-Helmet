package com.hudhelmet.controller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hudhelmet.controller.ui.theme.*
import com.hudhelmet.controller.viewmodel.HudViewModel

/**
 * Settings screen for configuring ESP32 IP address and sync options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HudViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToWifiConfig: () -> Unit
) {
    val ipAddress by viewModel.espIpAddress.collectAsState()
    val syncInterval by viewModel.syncInterval.collectAsState()
    val autoSyncEnabled by viewModel.autoSyncEnabled.collectAsState()
    val notifForwardingEnabled by viewModel.notifForwardingEnabled.collectAsState()
    val navigationEnabled by viewModel.navigationEnabled.collectAsState()

    var editableIp by remember { mutableStateOf(ipAddress) }
    var editableSyncInterval by remember { mutableStateOf(syncInterval.toString()) }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- ESP32 Connection Section ----
            SettingsSectionCard(
                icon = Icons.Default.Wifi,
                title = "ESP32 Connection",
                iconTint = HudCyan
            ) {
                // IP Address input
                OutlinedTextField(
                    value = editableIp,
                    onValueChange = { editableIp = it },
                    label = { Text("ESP32 IP Address") },
                    placeholder = { Text("192.168.1.100") },
                    leadingIcon = {
                        Icon(Icons.Default.Router, contentDescription = null, tint = HudCyan)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HudCyan,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = HudCyan,
                        cursorColor = HudCyan,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Save IP button
                Button(
                    onClick = {
                        viewModel.setEspIpAddress(editableIp.trim())
                        viewModel.checkConnection()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HudCyan,
                        contentColor = DarkBackground
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save & Connect", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Configure Wi-Fi button
                OutlinedButton(
                    onClick = onNavigateToWifiConfig,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HudCyan
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(
                        Icons.Default.SettingsApplications,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cấu hình Wi-Fi cho Mũ", fontWeight = FontWeight.Medium)
                }
            }

            // ---- Time Sync Section ----
            SettingsSectionCard(
                icon = Icons.Default.Schedule,
                title = "Time Synchronization",
                iconTint = HudMagenta
            ) {
                // Auto sync toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Auto Sync Time",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Automatically send time to ESP32",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = { viewModel.setAutoSync(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HudMagenta,
                            checkedTrackColor = HudMagentaDark.copy(alpha = 0.4f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceVariant,
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sync interval
                OutlinedTextField(
                    value = editableSyncInterval,
                    onValueChange = { newVal ->
                        editableSyncInterval = newVal.filter { it.isDigit() }
                    },
                    label = { Text("Sync Interval (seconds)") },
                    placeholder = { Text("30") },
                    leadingIcon = {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = HudMagenta)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HudMagenta,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = HudMagenta,
                        cursorColor = HudMagenta,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Apply interval button
                OutlinedButton(
                    onClick = {
                        val interval = editableSyncInterval.toIntOrNull() ?: 30
                        viewModel.setSyncInterval(interval)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HudMagenta
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("Apply Interval", fontWeight = FontWeight.Medium)
                }
            }

            // ---- Notification Forwarding Section ----
            SettingsSectionCard(
                icon = Icons.Default.Notifications,
                title = "Chuyển tiếp thông báo",
                iconTint = HudCyan
            ) {
                // Notification forwarding toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tự động chuyển tiếp",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Gửi thông báo điện thoại lên HUD",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = notifForwardingEnabled,
                        onCheckedChange = { viewModel.setNotifForwardingEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HudCyan,
                            checkedTrackColor = HudCyan.copy(alpha = 0.4f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceVariant,
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Permission hint
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = HudYellow,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cần cấp quyền Notification Access:\nCài đặt → Ứng dụng → Quyền truy cập thông báo → Bật HUD Helmet",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // ---- Navigation Section ----
            SettingsSectionCard(
                icon = Icons.Default.Navigation,
                title = "Điều hướng Google Maps",
                iconTint = HudMagenta
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sync điều hướng",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Gửi hướng dẫn Google Maps lên HUD (chế độ Nav)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = navigationEnabled,
                        onCheckedChange = { viewModel.setNavigationEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HudMagenta,
                            checkedTrackColor = HudMagentaDark.copy(alpha = 0.4f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceVariant,
                        )
                    )
                }
            }

            // ---- About Section ----
            SettingsSectionCard(
                icon = Icons.Default.Info,
                title = "About",
                iconTint = HudYellow
            ) {
                InfoRow("App Version", "1.0.0")
                InfoRow("Device", "ESP32 + ST7789 TFT")
                InfoRow("Display", "240×240 pixels (ST7789)")
                InfoRow("Protocol", "HTTP / JSON")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    iconTint: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = DarkCardBorder,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
    }
}
