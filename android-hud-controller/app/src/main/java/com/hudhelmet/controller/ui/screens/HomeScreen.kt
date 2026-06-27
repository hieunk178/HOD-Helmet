package com.hudhelmet.controller.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hudhelmet.controller.model.ConnectionState
import com.hudhelmet.controller.ui.components.*
import com.hudhelmet.controller.ui.theme.*
import com.hudhelmet.controller.viewmodel.HudViewModel

/**
 * Main home screen showing connection status, time sync, and notification controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HudViewModel,
    onNavigateToSettings: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val ipAddress by viewModel.espIpAddress.collectAsState()
    val autoSyncEnabled by viewModel.autoSyncEnabled.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val notifTitle by viewModel.notifTitle.collectAsState()
    val notifMessage by viewModel.notifMessage.collectAsState()
    val sentNotifications by viewModel.sentNotifications.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Show status messages as snackbar
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearStatusMessage()
        }
    }

    // Auto-connect on launch
    LaunchedEffect(Unit) {
        viewModel.checkConnection()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DarkSurfaceVariant,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        containerColor = DarkBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "HUD",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = HudCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Helmet",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Light,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DarkBackground
                )
            )
        }
    ) { innerPadding ->
        HomeScreenContent(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        )
    }
}

/**
 * Reusable content layout for HomeScreen, displayed inside the bottom navigation tab.
 */
@Composable
fun HomeScreenContent(
    viewModel: HudViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val ipAddress by viewModel.espIpAddress.collectAsState()
    val autoSyncEnabled by viewModel.autoSyncEnabled.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val notifTitle by viewModel.notifTitle.collectAsState()
    val notifMessage by viewModel.notifMessage.collectAsState()
    val sentNotifications by viewModel.sentNotifications.collectAsState()

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Connection Status Card
        item {
            ConnectionCard(
                connectionState = connectionState,
                ipAddress = ipAddress,
                onReconnect = { viewModel.checkConnection() }
            )
        }

        // Time Sync Card
        item {
            TimeSyncCard(
                isAutoSyncEnabled = autoSyncEnabled,
                lastSyncTime = lastSyncTime,
                onSyncNow = { viewModel.sendTimeNow() },
                onToggleAutoSync = { viewModel.setAutoSync(it) },
                isConnected = connectionState == ConnectionState.CONNECTED
            )
        }

        // Notification Card
        item {
            NotificationCard(
                title = notifTitle,
                message = notifMessage,
                onTitleChange = { viewModel.setNotifTitle(it) },
                onMessageChange = { viewModel.setNotifMessage(it) },
                onSend = { viewModel.sendNotification() },
                onClear = { viewModel.clearNotification() },
                isConnected = connectionState == ConnectionState.CONNECTED
            )
        }

        // Recent Notifications History
        if (sentNotifications.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recent Notifications",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            }

            items(sentNotifications) { notification ->
                NotificationHistoryItem(notification = notification)
            }
        }
    }
}
