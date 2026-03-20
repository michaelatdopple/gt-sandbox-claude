package com.dopple.webview.ui.test

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dopple.webview.R
import com.dopple.webview.bridge.LoopSDKInjector
import com.dopple.webview.bridge.WebAppInterface

/**
 * BLE JavaScript E2E test fragment.
 *
 * Loads a self-contained HTML test page with the full native bridge attached,
 * enabling scripted JS-level tests of every Loop.ble.* SDK function.
 *
 * Activated via deep link:
 *   dopple://test/ble-js?role=host&scenario=seed-play
 *   dopple://test/ble-js?role=client&scenario=seed-play
 *
 * Console output is routed to logcat tag "BleJsTest" for the Node.js driver
 * to parse structured PASS/FAIL results.
 */
class BleJsTestFragment : Fragment() {

    companion object {
        private const val TAG = "BleJsTest"
    }

    private var webView: WebView? = null
    private var bridge: WebAppInterface? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ble_js_test, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val role = arguments?.getString("role") ?: "client"
        val scenario = arguments?.getString("scenario") ?: "seed-play"
        Log.i(TAG, "Starting: role=$role scenario=$scenario")

        val wv = view.findViewById<WebView>(R.id.test_webview)
        webView = wv

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.let { LoopSDKInjector.inject(it, requireContext()) }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.i(TAG, it.message())
                }
                return true
            }
        }

        bridge = WebAppInterface(
            wv,
            requireActivity(),
            { null },
            lifecycleScope
        ).also { b ->
            @Suppress("JavascriptInterface") // ButtonNamespace is event-only (no JS-callable methods)
            wv.addJavascriptInterface(b.button, "Loop\$buttons")
            wv.addJavascriptInterface(b.imu, "Loop\$motion")
            wv.addJavascriptInterface(b.haptics, "Loop\$haptics")
            wv.addJavascriptInterface(b.match, "Loop\$match")
            wv.addJavascriptInterface(b.pack, "Loop\$pack")
            wv.addJavascriptInterface(b.manifest, "Loop\$manifest")
            wv.addJavascriptInterface(b.ble, "Loop\$ble")
        }

        wv.loadUrl("file:///android_asset/test/ble-e2e.html?role=$role&scenario=$scenario")
    }

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        bridge = null
        super.onDestroyView()
    }
}
