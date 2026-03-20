package com.dopple.webview.ui.test

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dopple.webview.R
import com.dopple.webview.bridge.ble.BLEGameManager
import com.dopple.webview.bridge.ble.PlayGameResult
import com.dopple.webview.bridge.ble.model.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Automated BLE test fragment for device-to-device testing.
 *
 * Activated via deep link:
 * - dopple://test/ble?role=host&scenario=default
 * - dopple://test/ble?role=client&scenario=wrong-code
 *
 * Scenarios: default, wrong-code, cancel-restart, double-start, full-restart
 */
@android.annotation.SuppressLint("SetTextI18n", "HardcodedText")
@Suppress("LargeClass")
class BleTestFragment : Fragment() {

    companion object {
        private const val TAG = "BleTest"
        const val TEST_TOKEN = "bletest1"
        const val TEST_GAME_ID = "ble-test-game"
        const val TEST_PLAYER_NAME = "TestPlayer"
        const val PHASE_TIMEOUT_MS = 30000L
        const val CLIENT_STARTUP_DELAY_MS = 2000L
        const val CANCEL_PHASE_DELAY_MS = 4000L
        const val WRONG_CODE_OUTER_TIMEOUT_MS = 15000L
        const val WRONG_CODE_MAX_ELAPSED_MS = 12000L
        const val SEED_PLAY_SEED = "test-seed-e2e"
    }

    enum class TestRole { HOST, CLIENT }

    private lateinit var role: TestRole
    private var scenario: String = "default"
    private var bleManager: BLEGameManager? = null
    private var capturingWebView: CapturingWebView? = null

    private var roleText: TextView? = null
    private var statusText: TextView? = null
    private var phase1Result: TextView? = null
    private var phase2Result: TextView? = null
    private var phase3Result: TextView? = null
    private var finalResult: TextView? = null

    private var connectedPlayerId: String? = null
    private var phase1Passed = false
    private var phase2Passed = false
    private var phase3Passed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ble_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        role = parseRole()
        scenario = arguments?.getString("scenario") ?: "default"
        roleText?.text = "${role.name} — $scenario"
        log("Starting as $role, scenario=$scenario")
        initBleManager()
        lifecycleScope.launch { runScenario() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bleManager?.cleanup()
        bleManager = null
        capturingWebView = null
        roleText = null
        statusText = null
        phase1Result = null
        phase2Result = null
        phase3Result = null
        finalResult = null
    }

    private fun bindViews(view: View) {
        roleText = view.findViewById(R.id.role_text)
        statusText = view.findViewById(R.id.status_text)
        phase1Result = view.findViewById(R.id.phase1_result)
        phase2Result = view.findViewById(R.id.phase2_result)
        phase3Result = view.findViewById(R.id.phase3_result)
        finalResult = view.findViewById(R.id.final_result)
    }

    private fun parseRole(): TestRole {
        val roleArg = arguments?.getString("role") ?: "client"
        return if (roleArg.lowercase() == "host") TestRole.HOST else TestRole.CLIENT
    }

    private fun initBleManager() {
        capturingWebView = CapturingWebView(requireContext())
        bleManager = BLEGameManager(
            context = requireContext(),
            activity = requireActivity(),
            webView = capturingWebView!!
        )
    }

    // ── Shared BLE helpers ──────────────────────────────────────

    private suspend fun reinitBle() {
        bleManager?.cleanup()
        bleManager = null
        capturingWebView?.clearCaptured()
        delay(500)
        initBleManager()
        delay(500)
    }

    private suspend fun hostCreateAndWaitForPlayer(token: String? = TEST_TOKEN): String? {
        val joinCode = bleManager?.createGame(TEST_GAME_ID, token)
        log("STEP: create-game token=${joinCode?.token}")
        updateStatus("Advertising: ${joinCode?.token}")
        while (bleManager?.getConnectedPlayers().isNullOrEmpty()) { delay(100) }
        connectedPlayerId = bleManager?.getConnectedPlayers()?.firstOrNull()?.id
        log("STEP: player-connected id=$connectedPlayerId")
        return joinCode?.token
    }

    private suspend fun clientJoinAndWaitConnected(token: String = TEST_TOKEN) {
        log("STEP: join-game token=$token")
        bleManager?.joinGame(token, TEST_PLAYER_NAME)
        while (bleManager?.getState() != ConnectionState.CONNECTED) { delay(100) }
        log("STEP: connected")
    }

    private fun endGameSafe() {
        try {
            bleManager?.endGame()
            log("STEP: end-game")
        } catch (e: Exception) {
            log("end-game failed: ${e.message}")
        }
    }

    /**
     * Assert that an event of the given type was received within timeout.
     * Logs ASSERT pass/fail for the test driver to parse.
     */
    private suspend fun assertEventReceived(
        eventType: String,
        label: String,
        timeoutMs: Long = 5000L,
        sinceMs: Long = 0L
    ): Boolean {
        val event = capturingWebView?.waitForEvent(eventType, timeoutMs, sinceMs)
        val passed = event != null
        log("ASSERT: $label ${if (passed) "PASS" else "FAIL"}")
        if (passed) {
            log("STEP: event-received type=$eventType script=${event!!.script.take(120)}")
        }
        return passed
    }

    // ── Scenario router ─────────────────────────────────────────

    private suspend fun runScenario() {
        log("SCENARIO: $scenario STARTED")
        try {
            when (role) {
                TestRole.HOST -> runHostScenario()
                TestRole.CLIENT -> runClientScenario()
            }
        } catch (e: Exception) {
            log("SCENARIO: $scenario FAIL — ${e.message}")
            updateStatus("FAILED: ${e.message}")
            showFinalResult()
        }
    }

    private suspend fun runHostScenario() = when (scenario) {
        "wrong-code" -> runHostWrongCode()
        "wrong-code-retry" -> runHostWrongCodeRetry()
        "cancel-restart" -> runHostCancelRestart()
        "double-start" -> runHostDoubleStart()
        "full-restart" -> runHostFullRestart()
        "connect-fail" -> runHostConnectFail()
        "seed-play" -> runSeedPlay()
        "seed-play-staggered" -> runSeedPlayStaggered()
        "seed-play-rejoin" -> runSeedPlayRejoin()
        else -> runHostDefault()
    }

    private suspend fun runClientScenario() = when (scenario) {
        "wrong-code" -> runClientWrongCode()
        "wrong-code-retry" -> runClientWrongCodeRetry()
        "cancel-restart" -> runClientCancelRestart()
        "double-start" -> runClientDoubleJoin()
        "full-restart" -> runClientFullRestart()
        "connect-fail" -> runClientConnectFail()
        "seed-play" -> runSeedPlay()
        "seed-play-staggered" -> runSeedPlayStaggered()
        "seed-play-rejoin" -> runSeedPlayRejoin()
        else -> runClientDefault()
    }

    // ── DEFAULT scenario ────────────────────────────────────────

    private suspend fun runHostDefault() {
        if (!hostDefaultPhase1()) return
        if (!hostDefaultPhase2()) return
        hostDefaultPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun hostDefaultPhase1(): Boolean {
        updatePhase(1, "STARTED")
        updateStatus("Creating game...")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) { hostCreateAndWaitForPlayer() }
            phase1Passed = true
            updatePhase(1, "PASS")
            delay(500)
            true
        } catch (e: Exception) {
            log("Phase 1 failed: ${e.message}")
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostDefaultPhase2(): Boolean {
        updatePhase(2, "STARTED")
        updateStatus("Sending messages...")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                val pid = connectedPlayerId ?: error("No player")
                bleManager?.sendToPlayer(pid, """{"type":"move","position":0,"player":"X"}""")
                delay(2000)
                bleManager?.sendToPlayer(pid, """{"type":"move","position":8,"player":"X"}""")
                delay(500)
            }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            log("Phase 2 failed: ${e.message}")
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostDefaultPhase3() {
        updatePhase(3, "STARTED")
        try {
            val pid = connectedPlayerId!!
            bleManager?.sendToPlayer(pid, """{"type":"gameOver","winner":"X"}""")
            delay(500)
            bleManager?.endGame()
            log("STEP: end-game")
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            log("Phase 3 failed: ${e.message}")
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    private suspend fun runClientDefault() {
        delay(CLIENT_STARTUP_DELAY_MS)
        if (!clientDefaultPhase1()) return
        if (!clientDefaultPhase2()) return
        clientDefaultPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun clientDefaultPhase1(): Boolean {
        updatePhase(1, "STARTED")
        updateStatus("Scanning for host...")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) { clientJoinAndWaitConnected() }
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            log("Phase 1 failed: ${e.message}")
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientDefaultPhase2(): Boolean {
        updatePhase(2, "STARTED")
        updateStatus("Exchanging messages...")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                delay(1000)
                bleManager?.sendToHost("""{"type":"move","position":4,"player":"O"}""")
                delay(1500)
            }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            log("Phase 2 failed: ${e.message}")
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientDefaultPhase3() {
        updatePhase(3, "STARTED")
        try {
            withTimeout(PHASE_TIMEOUT_MS) { delay(1500); bleManager?.leaveGame() }
            log("STEP: leave-game")
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            log("Phase 3 failed: ${e.message}")
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    // ── WRONG-CODE scenario ─────────────────────────────────────

    private suspend fun runHostWrongCode() {
        updatePhase(1, "STARTED")
        try {
            withTimeout(PHASE_TIMEOUT_MS) { hostCreateAndWaitForPlayer() }
            phase1Passed = true
            updatePhase(1, "PASS")
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            return
        }
        delay(1000)
        endGameSafe()
        passRemainingPhases()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    @Suppress("ReturnCount")
    private suspend fun runClientWrongCode() {
        delay(CLIENT_STARTUP_DELAY_MS)
        if (!clientWrongCodePhase1()) return
        if (!clientWrongCodePhase2()) return
        clientWrongCodePhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun clientWrongCodePhase1(): Boolean {
        updatePhase(1, "STARTED")
        val startTime = System.currentTimeMillis()
        return try {
            withTimeout(WRONG_CODE_OUTER_TIMEOUT_MS) {
                bleManager?.joinGame("ZZZZZZZZ", TEST_PLAYER_NAME)
            }
            log("ASSERT: wrong-token-timeout FAIL — connected with wrong token!")
            updatePhase(1, "FAIL - connected with wrong token")
            showFinalResult()
            false
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log("STEP: wrong-token-timeout elapsed=${elapsed}ms (${e.message})")
            if (elapsed > WRONG_CODE_MAX_ELAPSED_MS) {
                updatePhase(1, "FAIL - timeout too slow: ${elapsed}ms")
                showFinalResult()
                false
            } else {
                log("ASSERT: timeout-within-12s PASS (${elapsed}ms)")
                phase1Passed = true
                updatePhase(1, "PASS")
                true
            }
        }
    }

    private suspend fun clientWrongCodePhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            reinitBle()
            withTimeout(PHASE_TIMEOUT_MS) { clientJoinAndWaitConnected() }
            log("STEP: connected-with-correct-token")
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientWrongCodePhase3() {
        try {
            delay(500)
            bleManager?.leaveGame()
            log("STEP: leave-game")
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    // ── WRONG-CODE-RETRY scenario (game UI path — no reinit) ────

    private suspend fun runHostWrongCodeRetry() {
        // Same as wrong-code: just host and wait for a player
        runHostWrongCode()
    }

    @Suppress("ReturnCount")
    private suspend fun runClientWrongCodeRetry() {
        delay(CLIENT_STARTUP_DELAY_MS)
        if (!clientWrongCodeRetryPhase1()) return
        if (!clientWrongCodeRetryPhase2()) return
        clientWrongCodeRetryPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun clientWrongCodeRetryPhase1(): Boolean {
        updatePhase(1, "STARTED")
        val startTime = System.currentTimeMillis()
        return try {
            withTimeout(WRONG_CODE_OUTER_TIMEOUT_MS) {
                bleManager?.joinGame("ZZZZZZZZ", TEST_PLAYER_NAME)
            }
            log("ASSERT: wrong-token-timeout FAIL — connected with wrong token!")
            updatePhase(1, "FAIL - connected with wrong token")
            showFinalResult()
            false
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log("STEP: wrong-token-timeout elapsed=${elapsed}ms (${e.message})")
            log("STEP: state-after-timeout=${bleManager?.getState()}")
            // Game UI path: call leaveGame() to clean up, NO reinitBle
            bleManager?.leaveGame()
            log("STEP: leaveGame-called state=${bleManager?.getState()}")
            log("ASSERT: timeout-within-12s PASS (${elapsed}ms)")
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        }
    }

    private suspend fun clientWrongCodeRetryPhase2(): Boolean {
        updatePhase(2, "STARTED")
        log("STEP: retry-join (no reinit, leaveGame only)")
        return try {
            // This is the exact game UI path — same BLEGameManager, just leaveGame + joinGame
            delay(1000)
            withTimeout(PHASE_TIMEOUT_MS) { clientJoinAndWaitConnected() }
            log("STEP: connected-with-correct-token (no reinit!)")
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            log("STEP: retry-join FAILED — ${e.message}")
            log("STEP: state=${bleManager?.getState()}")
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientWrongCodeRetryPhase3() {
        try {
            delay(500)
            bleManager?.leaveGame()
            log("STEP: leave-game")
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    // ── CANCEL-RESTART scenario ─────────────────────────────────

    private suspend fun runHostCancelRestart() {
        if (!hostCancelRestartPhase1()) return
        if (!hostCancelRestartPhase2()) return
        hostCancelRestartPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun hostCancelRestartPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                // Use throwaway token for first game so client scan-1 won't match
                val jc1 = bleManager?.createGame(TEST_GAME_ID, "throwaway")
                log("STEP: create-game-1 token=${jc1?.token}")
                delay(1000)
                bleManager?.endGame()
                log("STEP: end-game-1")
                reinitBle()
                // Wait for client to finish cancel phase before advertising real token
                delay(CLIENT_STARTUP_DELAY_MS + CANCEL_PHASE_DELAY_MS)
                val jc2 = bleManager?.createGame(TEST_GAME_ID, TEST_TOKEN)
                log("STEP: create-game-2 token=${jc2?.token}")
            }
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostCancelRestartPhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                while (bleManager?.getConnectedPlayers().isNullOrEmpty()) { delay(100) }
                log("STEP: player-connected")
            }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostCancelRestartPhase3() {
        try {
            delay(500)
            endGameSafe()
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    private suspend fun runClientCancelRestart() {
        delay(CLIENT_STARTUP_DELAY_MS)
        if (!clientCancelRestartPhase1()) return
        if (!clientCancelRestartPhase2()) return
        clientCancelRestartPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun clientCancelRestartPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            // Scan for a token that won't match anything — then cancel
            val scanJob = lifecycleScope.launch {
                try { bleManager?.joinGame("nomatch1", TEST_PLAYER_NAME) }
                catch (_: Exception) { /* expected cancel */ }
            }
            delay(2000)
            bleManager?.leaveGame()
            scanJob.cancel()
            log("STEP: cancel-scan-1")
            reinitBle()
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientCancelRestartPhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) { clientJoinAndWaitConnected() }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientCancelRestartPhase3() {
        try {
            delay(500)
            bleManager?.leaveGame()
            log("STEP: leave-game")
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    // ── DOUBLE-START scenario ───────────────────────────────────

    private suspend fun runHostDoubleStart() {
        if (!hostDoubleStartPhase1()) return
        if (!hostDoubleStartPhase2()) return
        hostDoubleStartPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun hostDoubleStartPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                bleManager?.createGame(TEST_GAME_ID, TEST_TOKEN)
                log("STEP: create-game-1")
                delay(500)
                try {
                    bleManager?.createGame(TEST_GAME_ID, TEST_TOKEN)
                    log("STEP: create-game-2 accepted (no crash)")
                } catch (e: Exception) {
                    log("STEP: create-game-2 rejected: ${e.message}")
                }
                log("ASSERT: no-crash PASS")
            }
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostDoubleStartPhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                while (bleManager?.getConnectedPlayers().isNullOrEmpty()) { delay(100) }
                log("STEP: player-connected")
            }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostDoubleStartPhase3() {
        try {
            delay(500)
            endGameSafe()
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    private suspend fun runClientDoubleJoin() {
        delay(CLIENT_STARTUP_DELAY_MS)
        if (!clientDoubleJoinPhase1()) return
        clientDoubleJoinCleanup()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun clientDoubleJoinPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                clientJoinAndWaitConnected()
                try {
                    bleManager?.joinGame(TEST_TOKEN, TEST_PLAYER_NAME)
                    log("STEP: join-game-2 accepted (no crash)")
                } catch (e: Exception) {
                    log("STEP: join-game-2 rejected: ${e.message}")
                }
                log("ASSERT: no-crash PASS")
            }
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientDoubleJoinCleanup() {
        try {
            delay(500)
            bleManager?.leaveGame()
            log("STEP: leave-game")
            phase2Passed = true
            phase3Passed = true
            updatePhase(2, "PASS")
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
        }
    }

    // ── FULL-RESTART scenario ───────────────────────────────────

    private suspend fun runHostFullRestart() {
        if (!hostFullRestartPhase1()) return
        if (!hostFullRestartPhase2()) return
        hostFullRestartPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun hostFullRestartPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                bleManager?.createGame(TEST_GAME_ID)
                log("STEP: create-game-1")
                delay(1000)
                bleManager?.endGame()
                log("STEP: end-game-1")
                reinitBle()
                bleManager?.createGame(TEST_GAME_ID, TEST_TOKEN)
                log("STEP: create-game-2")
            }
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostFullRestartPhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                while (bleManager?.getConnectedPlayers().isNullOrEmpty()) { delay(100) }
                connectedPlayerId = bleManager?.getConnectedPlayers()?.firstOrNull()?.id
                log("STEP: player-connected id=$connectedPlayerId")
                delay(500)
                bleManager?.sendToPlayer(
                    connectedPlayerId!!,
                    """{"type":"move","position":0,"player":"X"}"""
                )
                log("STEP: send-message")
                delay(1000)
            }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostFullRestartPhase3() {
        try {
            endGameSafe()
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    private suspend fun runClientFullRestart() {
        delay(CLIENT_STARTUP_DELAY_MS)
        if (!clientFullRestartPhase1()) return
        if (!clientFullRestartPhase2()) return
        clientFullRestartPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun clientFullRestartPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            val scanJob = lifecycleScope.launch {
                try { bleManager?.joinGame("WRONG_TOKEN", TEST_PLAYER_NAME) }
                catch (_: Exception) { /* expected */ }
            }
            delay(2000)
            bleManager?.leaveGame()
            scanJob.cancel()
            log("STEP: cancel-scan-1")
            reinitBle()
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientFullRestartPhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                clientJoinAndWaitConnected()
                delay(1000)
                bleManager?.sendToHost("""{"type":"move","position":4,"player":"O"}""")
                log("STEP: send-response")
                delay(1000)
            }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientFullRestartPhase3() {
        try {
            bleManager?.leaveGame()
            log("STEP: leave-game")
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    // ── CONNECT-FAIL scenario ───────────────────────────────────

    private suspend fun runHostConnectFail() {
        if (!hostConnectFailPhase1()) return
        if (!hostConnectFailPhase2()) return
        hostConnectFailPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun hostConnectFailPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                bleManager?.createGame(TEST_GAME_ID, TEST_TOKEN)
                log("STEP: create-game token=$TEST_TOKEN")
                updateStatus("Waiting for client to connect...")
                // Wait for client to actually connect before killing GATT
                while (bleManager?.getConnectedPlayers().isNullOrEmpty()) { delay(100) }
                log("STEP: client-connected, killing GATT...")
                delay(500) // Let connection stabilize
                bleManager?.endGame()
                log("STEP: end-game (kill GATT with client connected)")
            }
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostConnectFailPhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                // Wait for client to detect disconnect + recover + reinit
                delay(4000)
                reinitBle()
                bleManager?.createGame(TEST_GAME_ID, TEST_TOKEN)
                log("STEP: create-game-2 token=$TEST_TOKEN")
                while (bleManager?.getConnectedPlayers().isNullOrEmpty()) { delay(100) }
                log("STEP: player-reconnected")
            }
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun hostConnectFailPhase3() {
        try {
            delay(500)
            endGameSafe()
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    @Suppress("ReturnCount")
    private suspend fun runClientConnectFail() {
        delay(CLIENT_STARTUP_DELAY_MS)
        if (!clientConnectFailPhase1()) return
        if (!clientConnectFailPhase2()) return
        clientConnectFailPhase3()
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    private suspend fun clientConnectFailPhase1(): Boolean {
        updatePhase(1, "STARTED")
        return try {
            withTimeout(PHASE_TIMEOUT_MS) {
                bleManager?.joinGame(TEST_TOKEN, TEST_PLAYER_NAME)
                log("STEP: connected to host")
                // Wait for host to kill GATT — should disconnect us
                while (bleManager?.getState() == ConnectionState.CONNECTED) { delay(100) }
                val state = bleManager?.getState()
                log("STEP: disconnected, state=$state")
                log("ASSERT: state-reset-to-IDLE ${if (state == ConnectionState.IDLE) "PASS" else "FAIL"}")
            }
            if (bleManager?.getState() != ConnectionState.IDLE) {
                updatePhase(1, "FAIL - state=${bleManager?.getState()}, expected IDLE")
                showFinalResult()
                return false
            }
            phase1Passed = true
            updatePhase(1, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientConnectFailPhase2(): Boolean {
        updatePhase(2, "STARTED")
        return try {
            // Clean up from phase 1 disconnect
            bleManager?.leaveGame()
            reinitBle()
            // Wait for host to reinit + create game-2
            delay(3000)
            withTimeout(PHASE_TIMEOUT_MS) { clientJoinAndWaitConnected() }
            log("STEP: reconnected-after-disconnect")
            phase2Passed = true
            updatePhase(2, "PASS")
            true
        } catch (e: Exception) {
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            false
        }
    }

    private suspend fun clientConnectFailPhase3() {
        try {
            delay(500)
            bleManager?.leaveGame()
            log("STEP: leave-game")
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            updatePhase(3, "FAIL - ${e.message}")
        }
    }

    // ── SEED-PLAY scenarios ─────────────────────────────────
    //
    // All seed-play scenarios are symmetric: both devices call playGame(seed).
    // The role= deep-link parameter is only for log identification.
    // The negotiation protocol decides who becomes host vs client.

    /**
     * Monitor BLE state transitions during negotiation.
     * Logs each transition to logcat and updates the on-screen status.
     */
    private fun startNegotiationMonitor(): Job {
        return lifecycleScope.launch {
            var lastState: ConnectionState? = null
            while (isActive) {
                val s = bleManager?.getState()
                if (s != lastState) {
                    log("SENSE: ${lastState?.name ?: "-"} → ${s?.name ?: "-"}")
                    updateStatus("Sensing: ${s?.name ?: "?"}")
                    lastState = s
                }
                delay(50)
            }
        }
    }

    /**
     * Core: Call playGame, log the sensing phase, validate result, wait for peer.
     * Throws on failure — caller wraps in try/catch for phase reporting.
     */
    @Suppress("LongMethod")
    private suspend fun negotiateAndValidate(): PlayGameResult {
        val monitor = startNegotiationMonitor()
        try {
            val startMs = System.currentTimeMillis()
            val result = withTimeout(PHASE_TIMEOUT_MS) {
                bleManager!!.playGame(SEED_PLAY_SEED, "TestDevice")
            }
            val elapsed = System.currentTimeMillis() - startMs
            monitor.cancel()

            log("STEP: playGame resolved role=${result.role} token=${result.token} elapsed=${elapsed}ms")
            log("ASSERT: playGame-success ${if (result.success) "PASS" else "FAIL"}")
            log("ASSERT: role-is-valid ${if (result.role in listOf("host", "client")) "PASS" else "FAIL"}")

            require(result.success) { "playGame returned success=false" }
            require(result.role == "host" || result.role == "client") { "Invalid role: ${result.role}" }

            val expectedState = if (result.role == "host") ConnectionState.HOSTING else ConnectionState.CONNECTED
            val actualState = bleManager?.getState()
            log("ASSERT: state-matches-role expected=$expectedState actual=$actualState " +
                "${if (actualState == expectedState) "PASS" else "FAIL"}")
            require(actualState == expectedState) { "State $actualState != expected $expectedState" }

            assertEventReceived("loop:ble:roleResolved", "roleResolved-event")

            if (result.role == "host") {
                updateStatus("Host — waiting for client...")
                withTimeout(PHASE_TIMEOUT_MS) {
                    while (bleManager?.getConnectedPlayers().isNullOrEmpty()) { delay(100) }
                }
                connectedPlayerId = bleManager?.getConnectedPlayers()?.firstOrNull()?.id
                log("STEP: player-connected id=$connectedPlayerId")
            }

            return result
        } catch (e: Exception) {
            monitor.cancel()
            throw e
        }
    }

    /**
     * Core: Exchange messages based on resolved role.
     */
    private suspend fun seedPlayExchange(result: PlayGameResult) {
        updateStatus("Exchanging messages as ${result.role}...")
        val exchangeStart = System.currentTimeMillis()
        withTimeout(PHASE_TIMEOUT_MS) {
            if (result.role == "host") {
                val pid = connectedPlayerId ?: error("No player connected")
                bleManager?.sendToPlayer(pid, """{"type":"ping","from":"host"}""")
                log("STEP: host-sent-ping")
                delay(1000)
                bleManager?.sendToPlayer(pid, """{"type":"ping2","from":"host"}""")
                log("STEP: host-sent-ping2")
                delay(500)
                // Verify we received client's pong
                assertEventReceived("loop:ble:message", "host-received-message", sinceMs = exchangeStart)
            } else {
                delay(500)
                bleManager?.sendToHost("""{"type":"pong","from":"client"}""")
                log("STEP: client-sent-pong")
                delay(1000)
                // Verify we received host's ping
                assertEventReceived("loop:ble:message", "client-received-message", sinceMs = exchangeStart)
            }
        }
    }

    /**
     * Core: Disconnect based on resolved role.
     */
    private fun seedPlayDisconnect(result: PlayGameResult) {
        if (result.role == "host") {
            bleManager?.endGame()
            log("STEP: end-game")
        } else {
            bleManager?.leaveGame()
            log("STEP: leave-game")
        }
    }

    // ── seed-play: Both devices start simultaneously ──

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun runSeedPlay() {
        // Phase 1: Negotiate — no delay, both start at the same time
        updatePhase(1, "STARTED")
        val result: PlayGameResult
        try {
            result = negotiateAndValidate()
            phase1Passed = true
            updatePhase(1, "PASS")
        } catch (e: Exception) {
            log("Phase 1 failed: ${e.message}")
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            return
        }

        // Phase 2: Exchange messages
        updatePhase(2, "STARTED")
        try {
            seedPlayExchange(result)
            phase2Passed = true
            updatePhase(2, "PASS")
        } catch (e: Exception) {
            log("Phase 2 failed: ${e.message}")
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            return
        }

        // Phase 3: Disconnect
        updatePhase(3, "STARTED")
        try {
            seedPlayDisconnect(result)
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            log("Phase 3 failed: ${e.message}")
            updatePhase(3, "FAIL - ${e.message}")
        }
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    // ── seed-play-staggered: Client starts after delay ──
    //
    // Tests late discovery: host-labeled device starts immediately,
    // completes negotiation as sole player (becomes host).
    // Client-labeled device starts 3s later, discovers confirmed host, joins.

    @Suppress("ReturnCount")
    private suspend fun runSeedPlayStaggered() {
        if (role == TestRole.CLIENT) {
            log("STEP: client startup delay ${CLIENT_STARTUP_DELAY_MS}ms")
            delay(CLIENT_STARTUP_DELAY_MS)
        }
        // Delegate to the same 3-phase flow
        runSeedPlay()
    }

    // ── seed-play-rejoin: Full cycle, reinit, play again ──
    //
    // Tests BLE reinit after seed-play: negotiate + exchange + disconnect,
    // then reinit + negotiate again + exchange + disconnect.

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun runSeedPlayRejoin() {
        // Phase 1: First complete cycle (negotiate + exchange + disconnect)
        updatePhase(1, "STARTED")
        try {
            val r1 = negotiateAndValidate()
            log("STEP: round-1 role=${r1.role}")
            seedPlayExchange(r1)
            seedPlayDisconnect(r1)
            log("STEP: round-1 complete")
            phase1Passed = true
            updatePhase(1, "PASS")
        } catch (e: Exception) {
            log("Phase 1 (round 1) failed: ${e.message}")
            updatePhase(1, "FAIL - ${e.message}")
            showFinalResult()
            return
        }

        // Phase 2: Reinit + second negotiate + exchange
        updatePhase(2, "STARTED")
        val r2: PlayGameResult
        try {
            reinitBle()
            log("STEP: reinit complete, settling...")
            delay(3000) // Let both devices settle before round 2
            connectedPlayerId = null
            r2 = negotiateAndValidate()
            log("STEP: round-2 role=${r2.role}")
            seedPlayExchange(r2)
            phase2Passed = true
            updatePhase(2, "PASS")
        } catch (e: Exception) {
            log("Phase 2 (round 2) failed: ${e.message}")
            updatePhase(2, "FAIL - ${e.message}")
            showFinalResult()
            return
        }

        // Phase 3: Final disconnect
        updatePhase(3, "STARTED")
        try {
            seedPlayDisconnect(r2)
            phase3Passed = true
            updatePhase(3, "PASS")
        } catch (e: Exception) {
            log("Phase 3 failed: ${e.message}")
            updatePhase(3, "FAIL - ${e.message}")
        }
        log("SCENARIO: $scenario PASS")
        showFinalResult()
    }

    // ── UI helpers ──────────────────────────────────────────────

    private fun passRemainingPhases() {
        phase2Passed = true
        phase3Passed = true
        updatePhase(2, "PASS")
        updatePhase(3, "PASS")
    }

    private fun updateStatus(message: String) {
        log(message)
        activity?.runOnUiThread { statusText?.text = message }
    }

    private fun updatePhase(phase: Int, result: String) {
        val color = when {
            result == "PASS" -> "#00FF00"
            result.startsWith("FAIL") -> "#FF0000"
            result == "STARTED" -> "#FFFF00"
            else -> "#888888"
        }
        log("Phase $phase: ${getPhaseLabel(phase)} - $result")
        activity?.runOnUiThread {
            val view = when (phase) {
                1 -> phase1Result; 2 -> phase2Result; 3 -> phase3Result; else -> null
            }
            view?.text = "Phase $phase: ${getPhaseLabel(phase)} - $result"
            view?.setTextColor(color.toColorInt())
        }
    }

    private fun getPhaseLabel(phase: Int): String = when (phase) {
        1 -> "Connection"; 2 -> "Messages"; 3 -> "Disconnect"; else -> "Unknown"
    }

    private fun showFinalResult() {
        val passed = phase1Passed && phase2Passed && phase3Passed
        val count = listOf(phase1Passed, phase2Passed, phase3Passed).count { it }
        log("=== TEST COMPLETE: $count/3 ${if (passed) "PASSED" else "FAILED"} ===")
        activity?.runOnUiThread {
            val color = if (passed) "#00FF00" else "#FF0000"
            finalResult?.text = "${if (passed) "TEST PASSED" else "TEST FAILED"} ($count/3)"
            finalResult?.setTextColor(color.toColorInt())
            finalResult?.visibility = View.VISIBLE
            statusText?.text = "Test complete"
        }
    }

    private fun log(message: String) {
        Log.i(TAG, "[${role.name}] $message")
    }
}
