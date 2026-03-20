package com.dopple.webview.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Utility class for network connectivity checks.
 * Provides methods to check if network is available before performing network operations.
 */
object NetworkUtils {

    /**
     * Checks if network connectivity is available.
     *
     * @param context The Android context
     * @return true if network is available, false otherwise
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Returns a user-friendly error message for offline state.
     */
    const val OFFLINE_ERROR_MESSAGE = "No internet connection"
}
