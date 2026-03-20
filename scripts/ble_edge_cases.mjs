#!/usr/bin/env node
/**
 * BLE Edge Case Test Driver (Layer 1)
 *
 * Drives BleTestFragment scenarios via ADB intents and monitors
 * logcat for structured SCENARIO/STEP/ASSERT output.
 */

import { execSync, spawn } from 'child_process';

const HOST_DEVICE = process.env.HOST_DEVICE;
const CLIENT_DEVICE = process.env.CLIENT_DEVICE;
const SCENARIO_TIMEOUT_MS = 30000;
const PKG = 'com.dopple.webview';

// Scenario definitions: which roles participate, order matters
const SCENARIOS = [
  {
    name: 'seed-play',
    description: 'Both devices call playGame(seed) simultaneously — negotiation elects host, exchange messages',
    roles: ['host', 'client'],
    hostDelay: 0,
  },
  {
    name: 'seed-play-staggered',
    description: 'Host starts immediately, client starts 3s late — late discovery of confirmed host',
    roles: ['host', 'client'],
    hostDelay: 0,
  },
  {
    name: 'seed-play-rejoin',
    description: 'Full seed-play cycle, disconnect, reinit, then play again — BLE reinit after negotiation',
    roles: ['host', 'client'],
    hostDelay: 0,
    timeoutMs: 60000,
  },
  {
    name: 'wrong-code-retry',
    description: 'Client scans wrong token, leaveGame only (no reinit), then retries with correct token',
    roles: ['host', 'client'],
    hostDelay: 3000,
  },
  {
    name: 'wrong-code',
    description: 'Client scans wrong token, times out, then connects with correct token',
    roles: ['host', 'client'],
    hostDelay: 3000,
  },
  {
    name: 'cancel-restart',
    description: 'Host/Client starts, cancels, restarts — BLE reinitializes cleanly',
    roles: ['host', 'client'],
    hostDelay: 3000,
  },
  {
    name: 'double-start',
    description: 'Host/Client calls create/join twice without cancel — no crash or leak',
    roles: ['host', 'client'],
    hostDelay: 3000,
  },
  {
    name: 'full-restart',
    description: 'Both cancel and restart, then full connect/message/disconnect works',
    roles: ['host', 'client'],
    hostDelay: 3000,
  },
  {
    name: 'connect-fail',
    description: 'Host kills GATT during client CONNECTING — client recovers to IDLE, then reconnects',
    roles: ['host', 'client'],
    hostDelay: 3000,
  },
];

function adb(device, ...args) {
  const cmd = `adb -s ${device} ${args.join(' ')}`;
  return execSync(cmd, { encoding: 'utf8', timeout: 10000 }).trim();
}

function forceStop(device) {
  try { adb(device, 'shell', 'am', 'force-stop', PKG); } catch {}
}

function clearLogcat(device) {
  try { adb(device, 'logcat', '-c'); } catch {}
}

function launchScenario(device, role, scenario) {
  adb(device, 'shell', 'am', 'start',
    '-a', 'android.intent.action.VIEW',
    '-d', `"dopple://test/ble?role=${role}\\&scenario=${scenario}"`,
    PKG);
}

function monitorLogcat(device, role) {
  return new Promise((resolve) => {
    const lines = [];
    const proc = spawn('adb', ['-s', device, 'logcat',
      '-s', 'BleTest:I', 'LoopNegotiator:D', 'LoopClientScanner:D',
      'LoopAdvertiser:D', 'BLEGameManager:D']);

    proc.stdout.on('data', (data) => {
      const text = data.toString();
      for (const line of text.split('\n')) {
        // Always capture BleTest lines for pass/fail detection
        if (line.includes('BleTest')) {
          lines.push(line);
          const match = line.match(/BleTest\s*:\s*(.*)/);
          if (match) console.log(`  ${match[1].trim()}`);
        }
        // Also display negotiation/sensing logs from BLE components
        else if (line.includes('LoopNegotiator') || line.includes('LoopClientScanner') ||
                 line.includes('LoopAdvertiser') || line.includes('BLEGameManager')) {
          const match = line.match(/:\s+(.*)/);
          if (match) console.log(`    [BLE] ${match[1].trim()}`);
        }
      }
    });

    resolve({ proc, lines });
  });
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function runScenario(scenario) {
  console.log(`\n${'─'.repeat(60)}`);
  console.log(`SCENARIO: ${scenario.name}`);
  console.log(`  ${scenario.description}`);
  console.log(`${'─'.repeat(60)}`);

  // Clean slate
  forceStop(HOST_DEVICE);
  forceStop(CLIENT_DEVICE);
  await sleep(1000);
  clearLogcat(HOST_DEVICE);
  clearLogcat(CLIENT_DEVICE);

  // Start logcat monitors
  const hostMon = await monitorLogcat(HOST_DEVICE, 'HOST');
  const clientMon = await monitorLogcat(CLIENT_DEVICE, 'CLIENT');

  // Launch host
  console.log(`  Launching HOST with scenario=${scenario.name}...`);
  launchScenario(HOST_DEVICE, 'host', scenario.name);

  if (scenario.roles.includes('client')) {
    await sleep(scenario.hostDelay || 3000);
    console.log(`  Launching CLIENT with scenario=${scenario.name}...`);
    launchScenario(CLIENT_DEVICE, 'client', scenario.name);
  }

  // Wait for completion
  const start = Date.now();
  let hostPassed = false;
  let clientPassed = false;
  const needsClient = scenario.roles.includes('client');

  const timeout = scenario.timeoutMs || SCENARIO_TIMEOUT_MS;
  while (Date.now() - start < timeout) {
    await sleep(500);

    const hostLines = hostMon.lines.join('\n');
    const clientLines = clientMon.lines.join('\n');

    if (hostLines.includes(`SCENARIO: ${scenario.name} PASS`)) hostPassed = true;
    if (hostLines.includes(`SCENARIO: ${scenario.name} FAIL`)) break;
    if (needsClient && clientLines.includes(`SCENARIO: ${scenario.name} PASS`)) clientPassed = true;
    if (needsClient && clientLines.includes(`SCENARIO: ${scenario.name} FAIL`)) break;

    // Also check for "TEST COMPLETE" with pass count
    if (hostLines.includes('TEST COMPLETE') && hostLines.includes('PASSED')) hostPassed = true;
    if (needsClient && clientLines.includes('TEST COMPLETE') && clientLines.includes('PASSED')) clientPassed = true;
    if (hostLines.includes('TEST COMPLETE') && hostLines.includes('FAILED')) break;
    if (needsClient && clientLines.includes('TEST COMPLETE') && clientLines.includes('FAILED')) break;

    if (hostPassed && (!needsClient || clientPassed)) break;
  }

  // Kill logcat processes
  hostMon.proc.kill();
  clientMon.proc.kill();

  const passed = hostPassed && (!needsClient || clientPassed);
  console.log(`\n  RESULT: ${scenario.name} ${passed ? 'PASS ✓' : 'FAIL ✗'}`);
  return passed;
}

async function main() {
  console.log('='.repeat(60));
  console.log('BLE Edge Case Tests (Layer 1 — Native Stack)');
  console.log('='.repeat(60));

  let passCount = 0;
  let failCount = 0;

  for (const scenario of SCENARIOS) {
    const passed = await runScenario(scenario);
    if (passed) passCount++;
    else failCount++;
  }

  console.log(`\n${'='.repeat(60)}`);
  console.log(`RESULTS: ${passCount} passed, ${failCount} failed (${SCENARIOS.length} total)`);
  console.log('='.repeat(60));

  process.exit(failCount > 0 ? 1 : 0);
}

main();
