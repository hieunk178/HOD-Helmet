package com.hudhelmet.controller.event

import android.graphics.Bitmap
import com.hudhelmet.controller.model.NavData
import com.hudhelmet.controller.model.RouteData
import com.hudhelmet.controller.model.RoutePoint
import com.hudhelmet.controller.service.NavigationEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Decoupled event bus for communication between Services and ViewModel.
 * Replaces the static HudViewModel.activeViewModel reference to prevent memory leaks.
 */
object HudEventBus {

    // -- Events from NotificationListenerService --
    data class NotificationSent(val title: String, val message: String, val success: Boolean)

    private val _notificationSentEvents = MutableSharedFlow<NotificationSent>(extraBufferCapacity = 16)
    val notificationSentEvents: SharedFlow<NotificationSent> = _notificationSentEvents.asSharedFlow()

    suspend fun emitNotificationSent(title: String, message: String, success: Boolean) {
        _notificationSentEvents.emit(NotificationSent(title, message, success))
    }

    // -- Events from NotificationListenerService (nav data from Google Maps) --
    data class NavDataEvent(val navData: NavData, val icon: Bitmap? = null)

    private val _navDataEvents = MutableSharedFlow<NavDataEvent>(extraBufferCapacity = 8)
    val navDataEvents: SharedFlow<NavDataEvent> = _navDataEvents.asSharedFlow()

    suspend fun emitNavData(navData: NavData, icon: Bitmap? = null) {
        _navDataEvents.emit(NavDataEvent(navData, icon))
    }

    // -- Nav clear event --
    private val _navClearEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val navClearEvents: SharedFlow<Unit> = _navClearEvents.asSharedFlow()

    suspend fun emitNavClear() {
        _navClearEvents.emit(Unit)
    }

    // -- Events from MapNavStreamService --
    data class NavigationStateUpdate(
        val currentLocation: RoutePoint,
        val currentSpeed: Float,
        val currentBearing: Float,
        val navUpdate: NavigationEngine.NavigationUpdate
    )

    private val _navigationStateUpdates = MutableSharedFlow<NavigationStateUpdate>(extraBufferCapacity = 8)
    val navigationStateUpdates: SharedFlow<NavigationStateUpdate> = _navigationStateUpdates.asSharedFlow()

    suspend fun emitNavigationStateUpdate(
        currentLocation: RoutePoint,
        currentSpeed: Float,
        currentBearing: Float,
        navUpdate: NavigationEngine.NavigationUpdate
    ) {
        _navigationStateUpdates.emit(NavigationStateUpdate(currentLocation, currentSpeed, currentBearing, navUpdate))
    }

    // -- Route update from service --
    private val _routeUpdates = MutableSharedFlow<RouteData>(extraBufferCapacity = 4)
    val routeUpdates: SharedFlow<RouteData> = _routeUpdates.asSharedFlow()

    suspend fun emitRouteUpdate(route: RouteData) {
        _routeUpdates.emit(route)
    }

    // -- Arrival event --
    private val _arrivalEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val arrivalEvents: SharedFlow<Unit> = _arrivalEvents.asSharedFlow()

    suspend fun emitArrival() {
        _arrivalEvents.emit(Unit)
    }
}
