// AUTO-GENERATED FILE — DO NOT HAND-EDIT
// Source: bridge-contract.yaml
// Regenerate: npm run generate
// Any manual changes will be overwritten on next generate.
package com.dopple.webview.bridge.generated

import android.webkit.JavascriptInterface

interface IMUNamespaceContract {
    @JavascriptInterface fun subscribe(): String
    @JavascriptInterface fun unsubscribe(id: String): Boolean
    @JavascriptInterface fun setFrequency(hz: Int): Int
    @JavascriptInterface fun setSensorFusion(type: String): Boolean
    @JavascriptInterface fun getStatus(): String
    @JavascriptInterface fun getLatest(): String?
    @JavascriptInterface fun getSensorAvailability(): String
}

interface ButtonNamespaceContract  // empty — no native methods

interface HapticsNamespaceContract {
    @JavascriptInterface fun getStatus(): String
    @JavascriptInterface fun pulse(intensity: Float): String
    @JavascriptInterface fun playCurve(curveJson: String): String
    @JavascriptInterface fun stop(): String
}

interface MatchNamespaceContract {
    @JavascriptInterface fun getStatus(): String
    @JavascriptInterface fun isBusy(): Boolean
    @JavascriptInterface fun captureFrame(requestId: Int, base64Image: String)
}

interface PackNamespaceContract {
    @JavascriptInterface fun getStatus(): String
    @JavascriptInterface fun getAsset(packName: String, path: String): String
    @JavascriptInterface fun assetExists(packName: String, path: String): Boolean
}

interface ManifestNamespaceContract {
    @JavascriptInterface fun load(url: String)
}

interface GalleryNamespaceContract {
    @JavascriptInterface fun getHistory(): String
    @JavascriptInterface fun launchActivity(manifestUrl: String)
    @JavascriptInterface fun prepareScanner()
    @JavascriptInterface fun openScanner()
    @JavascriptInterface fun closeScanner()
}

interface BLENamespaceContract {
    @JavascriptInterface fun createGame(gameId: String): String
    @JavascriptInterface fun createGame(gameId: String, token: String): String
    @JavascriptInterface fun joinGame(hostToken: String, playerName: String): String
    @JavascriptInterface fun playGame(seed: String, playerName: String): String
    @JavascriptInterface fun getState(): String
    @JavascriptInterface fun endGame()
    @JavascriptInterface fun leaveGame()
    @JavascriptInterface fun send(dataJson: String, optionsJson: String): String
}

interface StorageNamespaceContract {
    @JavascriptInterface fun setItem(key: String, value: String): String
    @JavascriptInterface fun getItem(key: String): String?
    @JavascriptInterface fun removeItem(key: String): String
    @JavascriptInterface fun clear(): String
    @JavascriptInterface fun keys(): String
    @JavascriptInterface fun getUsage(): String
    @JavascriptInterface fun exists(key: String): Boolean
}

interface SystemNamespaceContract {
    @JavascriptInterface fun isFreeRotateEnabled(): String
    @JavascriptInterface fun setFreeRotate(enabled: Boolean): String
}
