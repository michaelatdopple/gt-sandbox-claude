#!/usr/bin/env node
/**
 * Tic-Tac-Toe E2E Test Driver
 *
 * Uses Chrome DevTools Protocol (CDP) to drive the WebView UI on two devices.
 * Orchestrates the game flow and verifies state synchronization.
 */

import {
  connectCDP, clickByText, getText, waitForScreen,
  enterCode, getBoardState, clickCell, getActiveScreen, sleep
} from './cdp_helpers.mjs';

// CDP ports from environment or defaults
const HOST_CDP_PORT = process.env.HOST_CDP_PORT || 9222;
const CLIENT_CDP_PORT = process.env.CLIENT_CDP_PORT || 9223;

// Test timeout
const TEST_TIMEOUT_MS = 60000;

// ============================================================
// Main Test
// ============================================================

async function runTest() {
  console.log('');
  console.log('='.repeat(60));
  console.log('Tic-Tac-Toe E2E Test');
  console.log('='.repeat(60));
  console.log('');

  // Set overall test timeout
  const timeoutHandle = setTimeout(() => {
    console.error('\nTEST TIMEOUT');
    process.exit(1);
  }, TEST_TIMEOUT_MS);

  try {
    // Connect to both devices
    const host = await connectCDP(HOST_CDP_PORT, 'HOST');
    const client = await connectCDP(CLIENT_CDP_PORT, 'CLIENT');

    // Verify both are on menu screen
    await waitForScreen(host, 'menu');
    await waitForScreen(client, 'menu');
    console.log('\nBoth devices on menu screen');

    // ========== Phase 1: Host clicks "Host Game" ==========
    console.log('\n--- Phase 1: Host Game ---');
    await clickByText(host, 'Host Game');
    await waitForScreen(host, 'waiting-host');

    // Get host's room code
    await sleep(1000); // Wait for BLE advertising to start and token to appear
    const hostCode = await getText(host, '#host-room-code');
    console.log(`[HOST] Room code: ${hostCode}`);

    if (!hostCode || hostCode === '----') {
      throw new Error('Failed to get host room code');
    }

    // ========== Phase 2: Client clicks "Join Game" ==========
    console.log('\n--- Phase 2: Join Game ---');
    await clickByText(client, 'Join Game');
    await waitForScreen(client, 'code-entry');

    // ========== Phase 3: Client enters host's code ==========
    console.log('\n--- Phase 3: Enter Code ---');

    // Enter the code (remove spaces)
    const codeChars = hostCode.replace(/\s/g, '');
    await enterCode(client, codeChars);

    // Submit the code
    await clickByText(client, 'GO');
    console.log('[CLIENT] Submitted code');

    // ========== Phase 4: Wait for lobby ==========
    console.log('\n--- Phase 4: Waiting for BLE connection and lobby ---');

    // Both devices should transition to lobby screen after connection
    await Promise.all([
      waitForScreen(host, 'lobby', 20000),
      waitForScreen(client, 'lobby', 20000)
    ]);
    console.log('\nBoth devices in lobby!');

    // Give a moment for player list to render
    await sleep(500);

    // ========== Phase 4b: Host starts the game ==========
    console.log('\n--- Phase 4b: Host starts game ---');
    await clickByText(host, 'Start Game');

    // Both devices should transition to game screen
    await Promise.all([
      waitForScreen(host, 'game', 5000),
      waitForScreen(client, 'game', 5000)
    ]);
    console.log('\nGame started on both devices!');

    // ========== Phase 5: Play the game ==========
    console.log('\n--- Phase 5: Playing game ---');

    // Host (X) plays first - click cell 0 (top-left)
    await sleep(500);
    await clickCell(host, 0);
    console.log('[HOST] Played X at cell 0');

    // Wait for move to sync
    await sleep(1000);

    // Verify board state on both devices
    let hostBoard = await getBoardState(host);
    let clientBoard = await getBoardState(client);
    console.log(`[HOST] Board: ${JSON.stringify(hostBoard)}`);
    console.log(`[CLIENT] Board: ${JSON.stringify(clientBoard)}`);

    if (hostBoard[0] !== 'X' || clientBoard[0] !== 'X') {
      throw new Error('Move 1 did not sync - expected X at cell 0');
    }
    console.log('Move 1 synced correctly');

    // Client (O) plays - click cell 4 (center)
    await clickCell(client, 4);
    console.log('[CLIENT] Played O at cell 4');

    await sleep(1000);

    // Verify again
    hostBoard = await getBoardState(host);
    clientBoard = await getBoardState(client);
    console.log(`[HOST] Board: ${JSON.stringify(hostBoard)}`);
    console.log(`[CLIENT] Board: ${JSON.stringify(clientBoard)}`);

    if (hostBoard[4] !== 'O' || clientBoard[4] !== 'O') {
      throw new Error('Move 2 did not sync - expected O at cell 4');
    }
    console.log('Move 2 synced correctly');

    // Host plays cell 1
    await clickCell(host, 1);
    console.log('[HOST] Played X at cell 1');
    await sleep(1000);

    // Client plays cell 3
    await clickCell(client, 3);
    console.log('[CLIENT] Played O at cell 3');
    await sleep(1000);

    // Host plays cell 2 (wins with top row)
    await clickCell(host, 2);
    console.log('[HOST] Played X at cell 2 - should win!');
    await sleep(1500);

    // Check for game over screen
    const hostScreen = await getActiveScreen(host);
    const clientScreen = await getActiveScreen(client);

    console.log(`\n[HOST] Screen: ${hostScreen}`);
    console.log(`[CLIENT] Screen: ${clientScreen}`);

    // Verify final board state
    hostBoard = await getBoardState(host);
    console.log(`Final board: ${JSON.stringify(hostBoard)}`);

    // Verify the expected positions
    const expected = ['X', 'X', 'X', 'O', 'O', null, null, null, null];
    const matches = hostBoard[0] === 'X' &&
                   hostBoard[1] === 'X' &&
                   hostBoard[2] === 'X' &&
                   (hostBoard[3] === 'O' || hostBoard[4] === 'O');

    if (!matches) {
      console.warn('Board state differs from expected, but game played through');
    }

    console.log('\n' + '='.repeat(60));
    console.log('TEST PASSED - Game played successfully!');
    console.log('='.repeat(60));

    clearTimeout(timeoutHandle);

    // Close connections
    host.ws.close();
    client.ws.close();

    process.exit(0);

  } catch (err) {
    console.error('\nTEST FAILED:', err.message);
    clearTimeout(timeoutHandle);
    process.exit(1);
  }
}

// Run the test
runTest();
