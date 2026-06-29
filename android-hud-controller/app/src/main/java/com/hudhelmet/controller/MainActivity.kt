package com.hudhelmet.controller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hudhelmet.controller.ui.screens.HomeScreen
import com.hudhelmet.controller.ui.screens.SettingsScreen
import com.hudhelmet.controller.ui.screens.MainContainerScreen
import com.hudhelmet.controller.ui.theme.HUDHelmetTheme
import com.hudhelmet.controller.viewmodel.HudViewModel

/**
 * Main Activity for HUD Helmet Controller app.
 *
 * Provides navigation between Home and Settings screens
 * using Jetpack Compose Navigation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure osmdroid
        org.osmdroid.config.Configuration.getInstance().load(applicationContext, android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext))
        org.osmdroid.config.Configuration.getInstance().userAgentValue = "HUDHelmetApp"

        enableEdgeToEdge()

        requestLocationPermission()

        setContent {
            HUDHelmetTheme {
                HudHelmetApp()
            }
        }
    }

    private fun requestLocationPermission() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
        }
    }
}


@Composable
fun HudHelmetApp() {
    val navController = rememberNavController()
    val viewModel: HudViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = "main_container",
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300)
            ) + fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = tween(300)
            ) + fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(300)
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300)
            ) + fadeOut(animationSpec = tween(300))
        }
    ) {
        composable("main_container") {
            MainContainerScreen(
                viewModel = viewModel,
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToWifiConfig = {
                    navController.navigate("wifi_config")
                }
            )
        }

        composable("wifi_config") {
            com.hudhelmet.controller.ui.screens.WifiConfigScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
