package com.dopple.webview.bridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of seed-play negotiation.
 */
sealed class NegotiationResult {
    /** We won: become GATT server. Advertiser is still running with CONFIRMED_HOST_NONCE. */
    data class BecomeHost(val token: String, val advertiser: LoopAdvertiser) : NegotiationResult()
    /** We lost: connect to the confirmed host. */
    data class JoinHost(val token: String, val hostDevice: BluetoothDevice) : NegotiationResult()
}

/**
 * Single-phase staggered election engine.
 *
 * Protocol (Willard radio election adapted for BLE):
 * 1. Generate random 4-byte nonce (rank). Higher nonce = higher priority.
 * 2. Derive deterministic BLE token from seed: SHA-256(seed)[0:4].toHex()
 * 3. Compute scan duration inversely proportional to nonce rank.
 *    Highest rank scans shortest (500ms), lowest scans longest (3000ms).
 * 4. Advertise token+nonce (non-connectable) and scan simultaneously.
 * 5. If confirmed host discovered (nonce=0x00000000) → JoinHost immediately.
 * 6. When scan timer expires:
 *    - No confirmed host seen → self-promote (atomic lock transition to
 *      CONFIRMED_HOST_NONCE, connectable=true). Return BecomeHost.
 *    - Lower-ranked devices still scanning will see the lock bit and join.
 * 7. Fallback: if two timers overlap (very close nonces), a brief grace
 *    period resolves via nonce comparison + MAC tiebreak.
 */
class LoopNegotiator(
    private val bluetoothAdapter: BluetoothAdapter,
    private val scanner: LoopClientScanner,
    private val advertiser: LoopAdvertiser
) {
    companion object {
        private const val TAG = "LoopNegotiator"

        /** Number of SHA-256 digest bytes used to derive the token (4 bytes → 8 hex chars). */
        private const val TOKEN_DIGEST_BYTES = 4

        /** Mask for interpreting a 4-byte big-endian value as unsigned. */
        private const val UINT_MAX = 0xFFFFFFFFL

        /**
         * Derive a deterministic 8-char hex token from a seed string.
         * Uses first [TOKEN_DIGEST_BYTES] bytes of SHA-256 digest.
         */
        fun deriveToken(seed: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
            return digest.take(TOKEN_DIGEST_BYTES).joinToString("") { "%02x".format(it) }
        }

        /**
         * Compare two 4-byte nonces as unsigned big-endian integers.
         * @return positive if a > b, negative if a < b, 0 if equal
         */
        fun compareNonces(a: ByteArray, b: ByteArray): Int {
            val ua = ByteBuffer.wrap(a).int.toLong() and UINT_MAX
            val ub = ByteBuffer.wrap(b).int.toLong() and UINT_MAX
            return ua.compareTo(ub)
        }

        /**
         * Compute scan duration from nonce rank.
         * Higher nonce → shorter duration (promotes sooner).
         * Lower nonce → longer duration (waits to see confirmed host).
         */
        fun computeScanDuration(nonce: ByteArray): Long {
            val value = ByteBuffer.wrap(nonce).int.toLong() and UINT_MAX
            val normalized = value.toDouble() / UINT_MAX.toDouble()
            val range = LoopBleSpec.SCAN_DURATION_MAX_MS - LoopBleSpec.SCAN_DURATION_MIN_MS
            return LoopBleSpec.SCAN_DURATION_MAX_MS - (range * normalized).toLong()
        }
    }

    @Volatile private var cancelled = false

    /**
     * Run the single-phase staggered election.
     * Suspends until a role is determined.
     */
    @android.annotation.SuppressLint("MissingPermission", "HardwareIds")
    suspend fun negotiate(seed: String): NegotiationResult {
        cancelled = false

        val token = deriveToken(seed)

        // ── Step 1: Generate nonce (our rank) ──
        val myNonce = ByteArray(LoopBleSpec.NONCE_BYTE_LENGTH).also {
            SecureRandom().nextBytes(it)
            if (it.contentEquals(LoopBleSpec.CONFIRMED_HOST_NONCE)) {
                it[0] = 0x01
            }
        }

        val scanDuration = computeScanDuration(myNonce)
        Log.d(TAG, "Negotiating: token=$token, nonce=${myNonce.toHex()}, scanDuration=${scanDuration}ms")

        // Thread-safe result: first writer wins (confirmed host discovery > timer expiry)
        val resultRef = AtomicReference<NegotiationResult>(null)

        // Track peers for fallback resolution
        val discoveredPeers = mutableMapOf<String, ScanDiscovery>()

        // ── Step 2: Advertise + Scan simultaneously ──
        val scanStartMs = System.currentTimeMillis()
        advertiser.startAdvertising(token, myNonce, connectable = false)

        scanner.startContinuousScan(
            token = token,
            onDiscovery = { discovery ->
                if (resultRef.get() != null) return@startContinuousScan

                if (discovery.isConfirmedHost) {
                    val discoveryMs = System.currentTimeMillis() - scanStartMs
                    Log.i(TAG, "TIMING: discovery=${discoveryMs}ms confirmed_host=${discovery.device.address}")
                    resultRef.compareAndSet(null,
                        NegotiationResult.JoinHost(token, discovery.device))
                    return@startContinuousScan
                }

                // Track non-confirmed peer (for fallback only)
                synchronized(discoveredPeers) {
                    discoveredPeers[discovery.device.address] = discovery
                }
                Log.d(TAG, "Discovered peer: ${discovery.device.address}, nonce=${discovery.nonce.toHex()}")
            },
            onError = { e ->
                Log.w(TAG, "Scan error: ${e.message}")
            }
        )

        // ── Step 3: Wait for rank-dependent duration ──
        delay(scanDuration)

        val scanPhaseMs = System.currentTimeMillis() - scanStartMs
        Log.i(TAG, "TIMING: scan_phase=${scanPhaseMs}ms found_host=${resultRef.get() != null}")

        // Short-circuit if confirmed host found during scan
        resultRef.get()?.let {
            scanner.stopScan()
            advertiser.stopAdvertising()
            return it
        }
        if (cancelled) {
            scanner.stopScan()
            advertiser.stopAdvertising()
            throw NegotiationCancelledException()
        }

        // No confirmed host found — promote self and claim
        scanner.stopScan()
        val result = claimHostRole(token, myNonce, resultRef, discoveredPeers)
        val totalNegMs = System.currentTimeMillis() - scanStartMs
        val role = if (result is NegotiationResult.BecomeHost) "host" else "client"
        Log.i(TAG, "TIMING: negotiation_total=${totalNegMs}ms result=$role")
        return result
    }

    /**
     * Steps 4+5: Self-promote to confirmed host and run grace period.
     * If a competing confirmed host is discovered during grace, yield to it.
     */
    private suspend fun claimHostRole(
        token: String,
        myNonce: ByteArray,
        resultRef: AtomicReference<NegotiationResult>,
        discoveredPeers: Map<String, ScanDiscovery>
    ): NegotiationResult {
        Log.d(TAG, "Timer expired, promoting to confirmed host")

        // Atomic lock transition: swap election ad → confirmed host ad
        advertiser.transitionToConfirmedHost(token)

        // Grace period: check for competing confirmed hosts
        scanner.startContinuousScan(
            token = token,
            onDiscovery = { discovery ->
                if (resultRef.get() != null) return@startContinuousScan
                if (discovery.isConfirmedHost) {
                    val peerOriginalNonce = synchronized(discoveredPeers) {
                        discoveredPeers[discovery.device.address]?.nonce
                    }
                    if (peerOriginalNonce != null) {
                        val cmp = compareNonces(myNonce, peerOriginalNonce)
                        @Suppress("HardwareIds")
                        if (cmp < 0 || (cmp == 0 && (bluetoothAdapter.address ?: "") <= discovery.device.address)) {
                            Log.d(TAG, "Lost claim to ${discovery.device.address}, reverting")
                            resultRef.compareAndSet(null,
                                NegotiationResult.JoinHost(token, discovery.device))
                        }
                    }
                }
            },
            onError = { e -> Log.w(TAG, "Grace scan error: ${e.message}") }
        )

        delay(LoopBleSpec.CLAIM_GRACE_MS)
        scanner.stopScan()

        // If we lost during grace period, revert
        resultRef.get()?.let {
            advertiser.stopAdvertising()
            return it
        }

        Log.d(TAG, "Confirmed as host, advertiser running")
        return NegotiationResult.BecomeHost(token, advertiser)
    }

    /**
     * Cancel an in-progress negotiation.
     */
    fun cancel() {
        cancelled = true
        scanner.stopScan()
        advertiser.stopAdvertising()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}

/**
 * Thrown when negotiation is cancelled via [LoopNegotiator.cancel].
 */
class NegotiationCancelledException : Exception("Negotiation was cancelled")
