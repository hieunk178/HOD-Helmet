package com.hudhelmet.controller.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hudhelmet.controller.ui.theme.*
import com.hudhelmet.controller.viewmodel.HudViewModel

/**
 * Main container screen providing bottom navigation bar and hosting tab sub-screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContainerScreen(
    viewModel: HudViewModel,
    onNavigateToSettings: () -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
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
                    val title = when (currentMode) {
                        1 -> "HUD Helmet"
                        2 -> "Chỉ Đường"
                        3 -> "Stream Màn Hình"
                        else -> "HUD Helmet"
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
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
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentMode == 1,
                    onClick = { viewModel.setHudMode(1) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Mặc định") },
                    label = { Text("Mặc định") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = HudCyan,
                        selectedTextColor = HudCyan,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = currentMode == 2,
                    onClick = { viewModel.setHudMode(2) },
                    icon = { Icon(Icons.Default.Directions, contentDescription = "Chỉ đường") },
                    label = { Text("Chỉ đường") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = HudMagenta,
                        selectedTextColor = HudMagenta,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = currentMode == 3,
                    onClick = { viewModel.setHudMode(3) },
                    icon = { Icon(Icons.Default.Cast, contentDescription = "Stream màn hình") },
                    label = { Text("Screen Stream") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = HudYellow,
                        selectedTextColor = HudYellow,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            when (currentMode) {
                1 -> HomeScreenContent(viewModel = viewModel)
                2 -> DirectionsScreen(viewModel = viewModel)
                3 -> StreamScreen(viewModel = viewModel)
                else -> HomeScreenContent(viewModel = viewModel)
            }
        }
    }
}
