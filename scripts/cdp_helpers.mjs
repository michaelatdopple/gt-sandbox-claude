#!/usr/bin/env node
/**
 * Shared CDP (Chrome DevTools Protocol) helpers for E2E tests.
 * Extracted from tictactoe_e2e_driver.mjs for reuse across test files.
 */

import WebSocket from 'ws';

/**
 * Connect to a WebView via CDP
 */
export async function connectCDP(port, name) {
  console.log(`[${name}] Connecting to CDP on port ${port}...`);
  const response = await fetch(`http://localhost:${port}/json`);
  const pages = await response.json();

  let page = pages.find(p => p.url.includes('tictactoe'));
  if (!page) page = pages.find(p => p.type === 'page');
  if (!page) throw new Error(`No page found on port ${port}`);

  console.log(`[${name}] Found page: ${page.title || page.url}`);

  return new Promise((resolve, reject) => {
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    ws.on('open', () => {
      console.log(`[${name}] CDP connected`);
      resolve({ ws, name });
    });
    ws.on('error', (err) => reject(new Error(`CDP connection failed: ${err.message}`)));
    ws._pendingCommands = new Map();
    ws._cmdId = 1;
    ws.on('message', (data) => {
      const msg = JSON.parse(data.toString());
      if (msg.id && ws._pendingCommands.has(msg.id)) {
        const { resolve, reject } = ws._pendingCommands.get(msg.id);
        ws._pendingCommands.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      }
    });
  });
}

/**
 * Send CDP command and wait for response
 */
export function cdpCommand(conn, method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = conn.ws._cmdId++;
    conn.ws._pendingCommands.set(id, { resolve, reject });
    conn.ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      if (conn.ws._pendingCommands.has(id)) {
        conn.ws._pendingCommands.delete(id);
        reject(new Error(`Command timeout: ${method}`));
      }
    }, 10000);
  });
}

export async function click(conn, selector) {
  console.log(`[${conn.name}] Clicking: ${selector}`);
  const result = await cdpCommand(conn, 'Runtime.evaluate', {
    expression: `(() => { const el = document.querySelector('${selector}'); if (!el) throw new Error('Element not found: ${selector}'); el.click(); return true; })()`,
    returnByValue: true, awaitPromise: true
  });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception.description);
}

export async function clickByText(conn, text) {
  console.log(`[${conn.name}] Clicking button: "${text}"`);
  const result = await cdpCommand(conn, 'Runtime.evaluate', {
    expression: `(() => { const buttons = Array.from(document.querySelectorAll('button')); const btn = buttons.find(b => b.textContent.trim() === '${text}'); if (!btn) throw new Error('Button not found: ${text}'); btn.click(); return true; })()`,
    returnByValue: true, awaitPromise: true
  });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception.description);
}

export async function getText(conn, selector) {
  const result = await cdpCommand(conn, 'Runtime.evaluate', {
    expression: `document.querySelector('${selector}')?.textContent || ''`,
    returnByValue: true
  });
  return result.result.value;
}

export async function elementExists(conn, selector) {
  const result = await cdpCommand(conn, 'Runtime.evaluate', {
    expression: `!!document.querySelector('${selector}')`,
    returnByValue: true
  });
  return result.result.value;
}

export async function getActiveScreen(conn) {
  const result = await cdpCommand(conn, 'Runtime.evaluate', {
    expression: `document.querySelector('.screen.active')?.id || ''`,
    returnByValue: true
  });
  return result.result.value;
}

export async function waitForScreen(conn, screenId, timeout = 10000) {
  console.log(`[${conn.name}] Waiting for screen: ${screenId}`);
  const start = Date.now();
  while (Date.now() - start < timeout) {
    const active = await getActiveScreen(conn);
    if (active === screenId) {
      console.log(`[${conn.name}] Screen active: ${screenId}`);
      return true;
    }
    await sleep(100);
  }
  throw new Error(`Timeout waiting for screen: ${screenId}`);
}

export async function waitFor(conn, selector, timeout = 10000) {
  console.log(`[${conn.name}] Waiting for: ${selector}`);
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await elementExists(conn, selector)) return true;
    await sleep(100);
  }
  throw new Error(`Timeout waiting for: ${selector}`);
}

export async function enterCode(conn, code) {
  console.log(`[${conn.name}] Entering code: ${code}`);
  for (const char of code) {
    await cdpCommand(conn, 'Runtime.evaluate', {
      expression: `(() => { const keys = Array.from(document.querySelectorAll('.key')); const key = keys.find(k => k.textContent.trim() === '${char}'); if (key) key.click(); })()`
    });
    await sleep(150);
  }
}

export async function getBoardState(conn) {
  const result = await cdpCommand(conn, 'Runtime.evaluate', {
    expression: `(() => { const cells = document.querySelectorAll('.cell'); return Array.from(cells).map(c => { const span = c.querySelector('span'); return span ? span.textContent : null; }); })()`,
    returnByValue: true
  });
  return result.result.value;
}

export async function clickCell(conn, index) {
  console.log(`[${conn.name}] Clicking cell ${index}`);
  await cdpCommand(conn, 'Runtime.evaluate', {
    expression: `document.querySelectorAll('.cell')[${index}].click()`
  });
}

export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}
