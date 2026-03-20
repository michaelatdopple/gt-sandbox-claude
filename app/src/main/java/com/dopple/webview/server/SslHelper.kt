package com.dopple.webview.server

/**
 * Determines if a URL targets a local/private network host where
 * self-signed certificates are expected and acceptable.
 *
 * Matches: 127.0.0.1, localhost, 10.x.x.x, 172.x.x.x, 192.168.x.x
 */
object SslHelper {
    private val LOCAL_PREFIXES = listOf(
        "https://127.0.0.1",
        "https://localhost",
        "https://10.",
        "https://192.168.",
        "https://172.",
    )

    fun isLocalNetworkUrl(url: String): Boolean =
        LOCAL_PREFIXES.any { url.startsWith(it) }
}
