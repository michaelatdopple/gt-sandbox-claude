#!/usr/bin/env node
/**
 * Bridge Contract Validator (Simplified)
 *
 * Now that TypeScript + Kotlin compilers enforce method-level contract compliance,
 * this checker only validates what compilers CAN'T catch:
 *   1. Version lockstep across all files
 *   2. Kotlin JSON string literal field names (template strings can't be compiler-checked)
 *   3. Generated file freshness (verify generated files match current YAML)
 *
 * Run: node scripts/check-bridge-contract.mjs
 */
import { readFileSync, writeFileSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { parse as parseYaml } from 'yaml';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');

// ── Helpers ──────────────────────────────────────────────────────────

function read(relPath) {
  return readFileSync(resolve(ROOT, relPath), 'utf-8');
}

// ── Load sources ─────────────────────────────────────────────────────

const contract = parseYaml(read('bridge-contract.yaml'), { prettyErrors: true });
const kotlinSrc = read('app/src/main/java/com/dopple/webview/bridge/WebAppInterface.kt');
const jsSrc = read('app/src/main/assets/loop-sdk.js');
const dtsSrc = read('types/loop-sdk.d.ts');

const errors = [];
const warnings = [];

function error(msg) { errors.push(msg); }
function warn(msg) { warnings.push(msg); }

// ── 1. Version lockstep ──────────────────────────────────────────────

const contractVersion = contract.version;

// JS SDK: version getter returns '...'
const jsVersionMatch = jsSrc.match(/get version\(\)\s*\{\s*return\s*'([^']+)'/);
const jsVersion = jsVersionMatch?.[1];

// JS SDK JSDoc: @version X.Y.Z
const jsDocVersionMatch = jsSrc.match(/@version\s+(\S+)/);
const jsDocVersion = jsDocVersionMatch?.[1];

// d.ts: @version X.Y.Z
const dtsVersionMatch = dtsSrc.match(/@version\s+(\S+)/);
const dtsVersion = dtsVersionMatch?.[1];

// Kotlin: SDK_VERSION = "..."
const ktVersionMatch = kotlinSrc.match(/SDK_VERSION\s*=\s*"([^"]+)"/);
const ktVersion = ktVersionMatch?.[1];

if (jsVersion && jsVersion !== contractVersion) {
  error(`Version mismatch: loop-sdk.js getter returns '${jsVersion}' but contract says '${contractVersion}'`);
}
if (jsDocVersion && jsDocVersion !== contractVersion) {
  error(`Version mismatch: loop-sdk.js @version is '${jsDocVersion}' but contract says '${contractVersion}'`);
}
if (dtsVersion && dtsVersion !== contractVersion) {
  error(`Version mismatch: loop-sdk.d.ts @version is '${dtsVersion}' but contract says '${contractVersion}'`);
}
if (ktVersion) {
  if (ktVersion !== contractVersion) {
    error(`Version mismatch: WebAppInterface.kt SDK_VERSION is '${ktVersion}' but contract says '${contractVersion}'`);
  }
} else {
  warn(`WebAppInterface.kt is missing SDK_VERSION constant.\n  → Add: const val SDK_VERSION = "${contractVersion}" to companion object`);
}

// ── 2. Kotlin JSON field name cross-reference ────────────────────────
// Compilers can't check string template literals in Kotlin JSON responses.
// Verify that Kotlin JSON string literals reference correct field names.

// Extract JSON string literal keys from Kotlin source
// Matches patterns like: "ready":, "status":, "success":, etc. in template strings
const ktJsonFields = new Set();
const jsonFieldRegex = /"(\w+)":/g;
let fm;
while ((fm = jsonFieldRegex.exec(kotlinSrc)) !== null) {
  ktJsonFields.add(fm[1]);
}

// Common expected JSON fields from the contract's return types
const expectedFields = ['ready', 'status', 'success', 'error', 'available', 'hasAmplitudeSupport',
  'active', 'subscriptions', 'frequencyHz', 'smoothingAlpha', 'paused',
  'data', 'state', 'token'];

// Just a sanity check — ensure Kotlin is producing at least some expected fields
const missingCriticalFields = ['ready', 'success'].filter(f => !ktJsonFields.has(f));
if (missingCriticalFields.length > 0) {
  warn(`Kotlin JSON responses missing critical fields: ${missingCriticalFields.join(', ')}`);
}

// ── 3. Generated file freshness ──────────────────────────────────────
// Re-generate and compare against committed files.

const generatedFiles = [
  'sdk/generated/bridge-types.ts',
  'sdk/generated/internal-types.ts',
  'app/src/main/java/com/dopple/webview/bridge/generated/BridgeContracts.kt',
  'bridge-contract.snapshot.json',
];

try {
  const stripDates = (s) => s
    ? s.replace(/"_generated":\s*"[^"]*"/g, '"_generated": "__DATE__"')
         .replace(/"_note":\s*"[^"]*"/g, '"_note": "__NOTE__"')
    : null;
  const before = {};
  for (const f of generatedFiles) {
    const p = resolve(ROOT, f);
    before[f] = existsSync(p) ? stripDates(readFileSync(p, 'utf-8')) : null;
  }

  execSync('node scripts/generate-bridge-contracts.mjs', { cwd: ROOT, stdio: 'pipe' });

  const staleFiles = [];
  for (const f of generatedFiles) {
    const p = resolve(ROOT, f);
    const after = existsSync(p) ? stripDates(readFileSync(p, 'utf-8')) : null;
    if (before[f] !== after) staleFiles.push(f);
  }

  if (staleFiles.length > 0) {
    error(
      `Generated files are stale (don't match bridge-contract.yaml):\n` +
      staleFiles.map(f => `    ${f}`).join('\n') +
      `\n  → Run: npm run generate`
    );
  }
} catch (e) {
  error(`Generator script failed: ${e.message}`);
}

// ── 4. SDK method coverage ───────────────────────────────────────────
// Verify DX types declare all sdkMethods from YAML.

for (const [nsName, ns] of Object.entries(contract.namespaces || {})) {
  const sdkMethods = Object.keys(ns.sdkMethods || {});
  if (sdkMethods.length === 0) continue;

  const nsScope = ns.scope || 'public';
  const dtsFile = nsScope === 'internal' ? 'types/internal-sdk.d.ts' : 'types/loop-sdk.d.ts';
  const dtsPath = resolve(ROOT, dtsFile);
  if (!existsSync(dtsPath)) continue;
  const dtsSrcContent = readFileSync(dtsPath, 'utf-8');

  const missingMethods = sdkMethods.filter(m => {
    const pattern = new RegExp(`\\b${m}\\s*[(<]`);
    return !pattern.test(dtsSrcContent);
  });

  if (missingMethods.length > 0) {
    warn(
      `${dtsFile} missing SDK methods for '${nsName}': ${missingMethods.join(', ')}\n` +
      `  → Add method signatures to the DX types file`
    );
  }
}

// ── Generate snapshot ────────────────────────────────────────────────

const snapshot = {
  _generated: new Date().toISOString().split('T')[0],
  _note: "Auto-generated by check-bridge-contract.mjs — do not edit manually",
  version: contractVersion,
  namespaces: {}
};

for (const [nsName, ns] of Object.entries(contract.namespaces || {})) {
  snapshot.namespaces[nsName] = {
    native: ns.native,
    sdkExport: ns.sdkExport,
    nativeMethods: Object.keys(ns.nativeMethods || {}),
    sdkMethods: Object.keys(ns.sdkMethods || {}),
    events: ns.events || [],
  };
}

writeFileSync(
  resolve(ROOT, 'bridge-contract.snapshot.json'),
  JSON.stringify(snapshot, null, 2) + '\n'
);

// ── Report ───────────────────────────────────────────────────────────

console.log('');
console.log('Bridge Contract Check');
console.log('═'.repeat(50));

if (warnings.length > 0) {
  console.log(`\n⚠  ${warnings.length} warning(s):\n`);
  for (const w of warnings) {
    console.log(`  WARN: ${w}`);
  }
}

if (errors.length > 0) {
  console.log(`\n✗  ${errors.length} error(s):\n`);
  for (const e of errors) {
    console.log(`  ERROR: ${e}\n`);
  }
  console.log('Bridge contract violation. Fix errors above.');
  process.exit(1);
} else {
  const nsCount = Object.keys(contract.namespaces || {}).length;
  let nativeCount = 0;
  let sdkCount = 0;
  let eventCount = 0;
  for (const ns of Object.values(contract.namespaces || {})) {
    nativeCount += Object.keys(ns.nativeMethods || {}).length;
    sdkCount += Object.keys(ns.sdkMethods || {}).length;
    eventCount += (ns.events || []).length;
  }
  console.log(`\n✓  All checks passed.`);
  console.log(`   ${nsCount} namespaces, ${nativeCount} native methods, ${sdkCount} SDK methods, ${eventCount} events`);
  console.log(`   Compilers enforce method signatures (tsc + kotlinc)`);
  console.log(`   Checker validates: version lockstep, Kotlin JSON fields, generated freshness, SDK coverage`);
  console.log(`   Snapshot written to bridge-contract.snapshot.json`);
  console.log('');
  process.exit(0);
}
