#!/usr/bin/env node
/**
 * BLE JavaScript E2E Test Driver (Layer 2)
 *
 * Drives BleJsTestFragment scenarios via ADB intents and monitors
 * logcat for structured SCENARIO/STEP/ASSERT output from the JS test page.
 *
 * Usage:
 *   HOST_DEVICE=SERIAL1 CLIENT_DEVICE=SERIAL2 node scripts/ble_js_e2e.mjs
 */

import { execSync, spawn } from 'child_process';

const HOST_DEVICE = process.env.HOST_DEVICE;
const CLIENT_DEVICE = process.env.CLIENT_DEVICE;
const PKG = 'com.dopple.webview';

const SCENARIOS = [
  {
    name: 'seed-play',
    description: 'Both devices call playGame(seed) — negotiation, name propagation, messaging, disconnect',
    roles: ['host', 'client'],
    hostDelay: 0,
    timeoutMs: 45000,
  },
  {
    name: 'create-join',
    description: 'createGame + joinGame with pre-shared token — name assertion, messaging, disconnect',
    roles: ['host', 'client'],
    hostDelay: 3000,
    timeoutMs: 45000,
  },
  {
    name: 'event-off',
    description: 'Verify on/off handler removal — handler fires once, then off() prevents second fire',
    roles: ['host', 'client'],
    hostDelay: 3000,
    timeoutMs: 60000,
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
    '-d', `"dopple://test/ble-js?role=${role}\\&scenario=${scenario}"`,
    PKG);
}

function monitorLogcat(device) {
  return new Promise((resolve) => {
    const lines = [];
    const proc = spawn('adb', ['-s', device, 'logcat',
      '-s', 'BleJsTest:I', 'LoopNegotiator:D', 'LoopClientScanner:D',
      'LoopAdvertiser:D', 'BLEGameManager:D']);

    proc.stdout.on('data', (data) => {
      const text = data.toString();
      for (const line of text.split('\n')) {
        if (line.includes('BleJsTest')) {
          lines.push(line);
          const match = line.match(/BleJsTest\s*:\s*(.*)/);
          if (match) console.log(`  ${match[1].trim()}`);
        } else if (line.includes('LoopNegotiator') || line.includes('LoopClientScanner') ||
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

  forceStop(HOST_DEVICE);
  forceStop(CLIENT_DEVICE);
  await sleep(1000);
  clearLogcat(HOST_DEVICE);
  clearLogcat(CLIENT_DEVICE);

  const hostMon = await monitorLogcat(HOST_DEVICE);
  const clientMon = await monitorLogcat(CLIENT_DEVICE);

  console.log(`  Launching HOST with scenario=${scenario.name}...`);
  launchScenario(HOST_DEVICE, 'host', scenario.name);

  if (scenario.roles.includes('client')) {
    await sleep(scenario.hostDelay || 3000);
    console.log(`  Launching CLIENT with scenario=${scenario.name}...`);
    launchScenario(CLIENT_DEVICE, 'client', scenario.name);
  }

  const start = Date.now();
  let hostPassed = false;
  let clientPassed = false;
  const needsClient = scenario.roles.includes('client');
  const timeout = scenario.timeoutMs || 45000;

  while (Date.now() - start < timeout) {
    await sleep(500);

    const hostLines = hostMon.lines.join('\n');
    const clientLines = clientMon.lines.join('\n');

    if (hostLines.includes(`SCENARIO: ${scenario.name} PASS`)) hostPassed = true;
    if (hostLines.includes(`SCENARIO: ${scenario.name} FAIL`)) break;
    if (needsClient && clientLines.includes(`SCENARIO: ${scenario.name} PASS`)) clientPassed = true;
    if (needsClient && clientLines.includes(`SCENARIO: ${scenario.name} FAIL`)) break;

    if (hostPassed && (!needsClient || clientPassed)) break;
  }

  hostMon.proc.kill();
  clientMon.proc.kill();

  const passed = hostPassed && (!needsClient || clientPassed);
  console.log(`\n  RESULT: ${scenario.name} ${passed ? 'PASS ✓' : 'FAIL ✗'}`);
  return passed;
}

async function main() {
  console.log('='.repeat(60));
  console.log('BLE JavaScript E2E Tests (Layer 2 — JS SDK via Bridge)');
  console.log('='.repeat(60));

  if (!HOST_DEVICE || !CLIENT_DEVICE) {
    console.error('ERROR: Set HOST_DEVICE and CLIENT_DEVICE env vars');
    process.exit(1);
  }

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
