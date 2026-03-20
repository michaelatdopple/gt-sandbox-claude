package com.dopple.webview.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.dopple.loop.services.IItemMatcher
import com.dopple.loop.services.IPackManager
import com.dopple.settings.ISystemSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Manages connection to dopple-android's HostService via AIDL.
 * Provides access to IItemMatcher and IPackManager services.
 *
 * Features:
 * - State machine tracking connection lifecycle
 * - Automatic initialization polling (1s interval, 30s timeout)
 * - Auto-reconnection with exponential backoff on disconnect
 *
 * State flow: UNBOUND -> BINDING -> CONNECTED -> INITIALIZING -> READY
 */
class DoppleServiceManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DoppleServiceManager"

        // HostService binding configuration
        private const val HOST_SERVICE_PACKAGE = "com.dopple.loop.serviceshost"
        private const val ACTION_ITEM_MATCHER = "com.dopple.loop.serviceshost.action.ITEM_MATCHER"
        private const val ACTION_PACK_MANAGER = "com.dopple.loop.serviceshost.action.PACK_MANAGER"
        private const val ACTION_SYSTEM_SETTINGS = "com.dopple.loop.serviceshost.action.SYSTEM_SETTINGS"

        // Initialization polling configuration
        private const val POLL_INTERVAL_MS = 1000L
        private const val POLL_TIMEOUT_MS = 30000L

        // Reconnection configuration
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 32000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    /**
     * Service connection states.
     */
    enum class State {
        /** Not bound to service */
        UNBOUND,
        /** Binding in progress */
        BINDING,
        /** Service connected, both binders available */
        CONNECTED,
        /** Waiting for ItemMatcher/PackManager initialization */
        INITIALIZING,
        /** All services ready for use */
        READY,
        /** Error state - binding failed or service unavailable */
        ERROR
    }

    // State management
    private val _state = MutableStateFlow(State.UNBOUND)
    val state: StateFlow<State> = _state.asStateFlow()

    // Service references
    var itemMatcher: IItemMatcher? = null
        private set
    var packManager: IPackManager? = null
        private set
    var systemSettings: ISystemSettings? = null
        private set

    // Track which services are connected
    private var itemMatcherConnected = false
    private var packManagerConnected = false
    private var systemSettingsConnected = false

    // Job management
    private var initializationJob: Job? = null
    private var reconnectionJob: Job? = null

    // Reconnection tracking
    private var reconnectAttempts = 0
    private var currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS

    // Track if we're intentionally unbound (vs disconnect)
    private var intentionalUnbind = false

    /**
     * ServiceConnection for IItemMatcher.
     */
    private val itemMatcherConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "ItemMatcher service connected")
            itemMatcher = IItemMatcher.Stub.asInterface(service)
            itemMatcherConnected = true
            checkAllServicesConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "ItemMatcher service disconnected")
            itemMatcher = null
            itemMatcherConnected = false
            handleServiceDisconnect()
        }
    }

    /**
     * ServiceConnection for IPackManager.
     */
    private val packManagerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "PackManager service connected")
            packManager = IPackManager.Stub.asInterface(service)
            packManagerConnected = true
            checkAllServicesConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "PackManager service disconnected")
            packManager = null
            packManagerConnected = false
            handleServiceDisconnect()
        }
    }

    /**
     * ServiceConnection for ISystemSettings.
     * Optional — failure to bind is non-fatal.
     */
    private val systemSettingsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "SystemSettings service connected")
            systemSettings = ISystemSettings.Stub.asInterface(service)
            systemSettingsConnected = true
            checkAllServicesConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "SystemSettings service disconnected")
            systemSettings = null
            systemSettingsConnected = false
            handleServiceDisconnect()
        }
    }

    /**
     * Check if both services are connected and transition to CONNECTED state.
     */
    private fun checkAllServicesConnected() {
        if (itemMatcherConnected && packManagerConnected) {
            Log.d(TAG, "All services connected")
            _state.value = State.CONNECTED

            // Reset reconnection tracking on successful connect
            reconnectAttempts = 0
            currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS

            // Start initialization polling
            startInitializationPolling()
        }
    }

    /**
     * Handle service disconnection.
     */
    private fun handleServiceDisconnect() {
        initializationJob?.cancel()

        if (!intentionalUnbind) {
            _state.value = State.UNBOUND
            scheduleReconnection()
        }
    }

    /**
     * Binds to the HostService.
     * Call this in Activity.onCreate().
     */
    fun bind() {
        if (_state.value != State.UNBOUND && _state.value != State.ERROR) {
            Log.d(TAG, "Already bound or binding, current state: ${_state.value}")
            return
        }

        intentionalUnbind = false
        itemMatcherConnected = false
        packManagerConnected = false
        systemSettingsConnected = false
        _state.value = State.BINDING

        // Bind to ItemMatcher
        val itemMatcherIntent = Intent().apply {
            action = ACTION_ITEM_MATCHER
            setPackage(HOST_SERVICE_PACKAGE)
        }

        // Bind to PackManager
        val packManagerIntent = Intent().apply {
            action = ACTION_PACK_MANAGER
            setPackage(HOST_SERVICE_PACKAGE)
        }

        try {
            val itemMatcherBound = context.bindService(
                itemMatcherIntent,
                itemMatcherConnection,
                Context.BIND_AUTO_CREATE
            )

            val packManagerBound = context.bindService(
                packManagerIntent,
                packManagerConnection,
                Context.BIND_AUTO_CREATE
            )

            if (!itemMatcherBound || !packManagerBound) {
                Log.e(TAG, "Failed to bind to services (ItemMatcher: $itemMatcherBound, PackManager: $packManagerBound)")
                _state.value = State.ERROR
                scheduleReconnection()
            } else {
                Log.d(TAG, "Binding to HostService services initiated")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception binding to HostService", e)
            _state.value = State.ERROR
        }

        val systemSettingsIntent = Intent().apply {
            action = ACTION_SYSTEM_SETTINGS
            setPackage(HOST_SERVICE_PACKAGE)
        }
        val systemSettingsBound = try {
            context.bindService(systemSettingsIntent, systemSettingsConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind SystemSettings — LoopSettings shim may not be installed", e)
            false
        }
        if (!systemSettingsBound) {
            Log.w(TAG, "SystemSettings service not available — free rotate API will degrade gracefully")
        }
    }

    /**
     * Unbinds from the HostService.
     * Call this in Activity.onDestroy().
     */
    fun unbind() {
        intentionalUnbind = true
        initializationJob?.cancel()
        reconnectionJob?.cancel()

        if (itemMatcherConnected) {
            try {
                context.unbindService(itemMatcherConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "ItemMatcher service not registered", e)
            }
        }

        if (packManagerConnected) {
            try {
                context.unbindService(packManagerConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "PackManager service not registered", e)
            }
        }

        if (systemSettingsConnected) {
            try {
                context.unbindService(systemSettingsConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "SystemSettings service not registered", e)
            }
        }

        itemMatcher = null
        packManager = null
        systemSettings = null
        itemMatcherConnected = false
        packManagerConnected = false
        systemSettingsConnected = false
        _state.value = State.UNBOUND
        Log.d(TAG, "Services unbound")
    }

    /**
     * Polls for service initialization status.
     * ItemMatcher initialization can take several seconds after service connection.
     */
    private fun startInitializationPolling() {
        _state.value = State.INITIALIZING

        initializationJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var attempts = 0

            while (System.currentTimeMillis() - startTime < POLL_TIMEOUT_MS) {
                attempts++

                val matcherReady = try {
                    itemMatcher?.isInitialized == true
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking ItemMatcher status", e)
                    false
                }

                val packReady = try {
                    packManager?.packState == IPackManager.PACK_STATE_READY
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking PackManager status", e)
                    false
                }

                Log.d(TAG, "Init poll #$attempts: matcher=$matcherReady, pack=$packReady")

                if (matcherReady && packReady) {
                    _state.value = State.READY
                    Log.d(TAG, "Services initialized and ready")
                    return@launch
                }

                delay(POLL_INTERVAL_MS)
            }

            // Timeout reached - check if we have partial functionality
            val packReady = try {
                packManager?.packState == IPackManager.PACK_STATE_READY
            } catch (e: Exception) {
                false
            }

            if (packReady) {
                // PackManager ready, ItemMatcher may work later
                _state.value = State.READY
                Log.w(TAG, "Timeout: PackManager ready, ItemMatcher may still be initializing")
            } else {
                _state.value = State.ERROR
                Log.e(TAG, "Timeout waiting for services to initialize")
            }
        }
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     */
    private fun scheduleReconnection() {
        if (intentionalUnbind) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnection attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            _state.value = State.ERROR
            return
        }

        reconnectionJob?.cancel()
        reconnectionJob = scope.launch {
            Log.d(TAG, "Scheduling reconnection in ${currentReconnectDelay}ms (attempt ${reconnectAttempts + 1})")
            delay(currentReconnectDelay)

            if (!intentionalUnbind && _state.value == State.UNBOUND) {
                reconnectAttempts++
                // Exponential backoff with cap
                currentReconnectDelay = min(currentReconnectDelay * 2, MAX_RECONNECT_DELAY_MS)
                bind()
            }
        }
    }

    /**
     * Returns the current service status as a JSON string.
     */
    fun getStatusJson(): String {
        val matcherStatus = try {
            itemMatcher?.isInitialized?.toString() ?: "null"
        } catch (e: Exception) {
            "error"
        }

        val packState = try {
            packManager?.packState?.toString() ?: "null"
        } catch (e: Exception) {
            "error"
        }

        return buildString {
            append("{")
            append("\"state\":\"${_state.value}\",")
            append("\"itemMatcherInitialized\":$matcherStatus,")
            append("\"packState\":$packState,")
            append("\"reconnectAttempts\":$reconnectAttempts")
            append("}")
        }
    }
}
