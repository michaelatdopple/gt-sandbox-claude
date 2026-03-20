#!/usr/bin/env node
/**
 * Bridge Contract Generator
 *
 * Reads bridge-contract.yaml and generates:
 *   1. sdk/generated/bridge-types.ts       — Ambient TS types (zero JS emit)
 *   2. app/.../generated/BridgeContracts.kt — Kotlin interfaces
 *   3. bridge-contract.snapshot.json        — JSON snapshot for PR diffs
 *
 * Run: node scripts/generate-bridge-contracts.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { parse as parseYaml } from 'yaml';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');

function read(relPath) {
  return readFileSync(resolve(ROOT, relPath), 'utf-8');
}

// ── Type mapping ─────────────────────────────────────────────────────

function yamlTypeToTS(yamlType) {
  if (!yamlType) return 'void';
  const nullable = typeof yamlType === 'string' && yamlType.endsWith('?');
  const base = nullable ? yamlType.slice(0, -1) : yamlType;
  const map = {
    'String': 'string',
    'Int': 'number',
    'Float': 'number',
    'Boolean': 'boolean',
  };
  const ts = map[base] || 'string';
  return nullable ? `${ts} | null` : ts;
}

function yamlTypeToKotlin(yamlType) {
  if (!yamlType) return '';  // void — no return type annotation
  const nullable = typeof yamlType === 'string' && yamlType.endsWith('?');
  const base = nullable ? yamlType.slice(0, -1) : yamlType;
  const result = base;  // Kotlin types match YAML: String, Int, Float, Boolean
  return nullable ? `${result}?` : result;
}

// ── Parse params from YAML ──────────────────────────────────────────

function parseParams(params) {
  if (!params || !Array.isArray(params)) return [];
  return params.map(p => {
    let rawName, type;
    if (typeof p === 'object' && p !== null) {
      // yaml package parses [gameId: String] as [{ gameId: 'String' }]
      const key = Object.keys(p)[0];
      rawName = key;
      type = String(p[key]);
    } else {
      // Fallback for plain strings: "gameId: String" or "token?: String"
      const parts = String(p).split(':').map(s => s.trim());
      rawName = parts[0];
      type = parts[1] || 'String';
    }
    const optional = rawName.endsWith('?');
    const name = optional ? rawName.slice(0, -1) : rawName;
    return { name, type, optional };
  });
}

// ── Generate TypeScript ambient declarations ────────────────────────

function generateBridgeTypes(contract, scope) {
  const lines = [
    '// AUTO-GENERATED FILE — DO NOT HAND-EDIT',
    '// Source: bridge-contract.yaml',
    '// Regenerate: npm run generate',
    '// Any manual changes will be overwritten on next generate.',
    '',
    '// --- Native Bridge Globals ---',
  ];

  for (const [nsName, ns] of Object.entries(contract.namespaces || {})) {
    const nsScope = ns.scope || 'public';
    if (nsScope !== scope) continue;

    const bridgeName = ns.native; // e.g., "Loop$motion"
    if (!bridgeName) continue; // Skip namespaces with no native bridge (e.g., system)
    const interfaceName = bridgeName.replace('$', '$$'); // TypeScript interface name

    const nativeMethods = ns.nativeMethods || {};
    const methodNames = Object.keys(nativeMethods);

    if (methodNames.length === 0) {
      lines.push(`export declare interface ${interfaceName} {}`);
      lines.push('');
      continue;
    }

    lines.push(`export declare interface ${interfaceName} {`);
    for (const methodName of methodNames) {
      const spec = nativeMethods[methodName];
      const params = parseParams(spec.params);
      const hasOptional = params.some(p => p.optional);
      const returnType = yamlTypeToTS(spec.returns);

      if (hasOptional) {
        // Emit overloads: one with only required params, one with all params
        const requiredParams = params.filter(p => !p.optional);
        const reqStr = requiredParams.map(p => `${p.name}: ${yamlTypeToTS(p.type)}`).join(', ');
        const allStr = params.map(p => `${p.name}${p.optional ? '?' : ''}: ${yamlTypeToTS(p.type)}`).join(', ');
        lines.push(`    ${methodName}(${reqStr}): ${returnType};`);
        lines.push(`    ${methodName}(${allStr}): ${returnType};`);
      } else {
        const paramStr = params.map(p => `${p.name}: ${yamlTypeToTS(p.type)}`).join(', ');
        lines.push(`    ${methodName}(${paramStr}): ${returnType};`);
      }
    }
    lines.push('}');
    lines.push('');
  }

  // Window augmentation — `declare global` needed because exports make this a module
  lines.push('// --- Window augmentation ---');
  lines.push('declare global {');
  lines.push('    interface Window {');
  for (const [, ns] of Object.entries(contract.namespaces || {})) {
    const nsScope = ns.scope || 'public';
    if (nsScope !== scope) continue;
    const bridgeName = ns.native;
    if (!bridgeName) continue;
    const interfaceName = bridgeName.replace('$', '$$');
    lines.push(`        '${bridgeName}'?: ${interfaceName};`);
  }
  lines.push('    }');
  lines.push('}');
  lines.push('');

  return lines.join('\n');
}

// ── Generate Kotlin interfaces ──────────────────────────────────────

function generateKotlinContracts(contract) {
  const lines = [
    '// AUTO-GENERATED FILE — DO NOT HAND-EDIT',
    '// Source: bridge-contract.yaml',
    '// Regenerate: npm run generate',
    '// Any manual changes will be overwritten on next generate.',
    'package com.dopple.webview.bridge.generated',
    '',
    'import android.webkit.JavascriptInterface',
    '',
  ];

  for (const [, ns] of Object.entries(contract.namespaces || {})) {
    const kotlinClass = ns.kotlinClass;
    if (!kotlinClass) continue; // Skip namespaces with no Kotlin class (e.g., system)
    const interfaceName = `${kotlinClass}Contract`;
    const nativeMethods = ns.nativeMethods || {};
    const methodNames = Object.keys(nativeMethods);

    if (methodNames.length === 0) {
      lines.push(`interface ${interfaceName}  // empty — no native methods`);
      lines.push('');
      continue;
    }

    lines.push(`interface ${interfaceName} {`);
    for (const methodName of methodNames) {
      const spec = nativeMethods[methodName];
      const params = parseParams(spec.params);
      const hasOptional = params.some(p => p.optional);
      const returnType = yamlTypeToKotlin(spec.returns);
      const returnSuffix = returnType ? `: ${returnType}` : '';

      if (hasOptional) {
        // Android WebView dispatches @JavascriptInterface by name + arity,
        // so emit overloads: one with required params, one with all params.
        const requiredParams = params.filter(p => !p.optional);
        const reqStr = requiredParams.map(p => `${p.name}: ${yamlTypeToKotlin(p.type)}`).join(', ');
        const allStr = params.map(p => `${p.name}: ${yamlTypeToKotlin(p.type)}`).join(', ');
        lines.push(`    @JavascriptInterface fun ${methodName}(${reqStr})${returnSuffix}`);
        lines.push(`    @JavascriptInterface fun ${methodName}(${allStr})${returnSuffix}`);
      } else {
        const paramStr = params.map(p => `${p.name}: ${yamlTypeToKotlin(p.type)}`).join(', ');
        lines.push(`    @JavascriptInterface fun ${methodName}(${paramStr})${returnSuffix}`);
      }
    }
    lines.push('}');
    lines.push('');
  }

  return lines.join('\n');
}

// ── Generate JSON snapshot ──────────────────────────────────────────

function generateSnapshot(contract) {
  const snapshot = {
    _generated: new Date().toISOString().split('T')[0],
    _note: 'AUTO-GENERATED FILE — DO NOT HAND-EDIT. Source: bridge-contract.yaml. Regenerate: npm run generate.',
    version: contract.version,
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

  return JSON.stringify(snapshot, null, 2) + '\n';
}

// ── Main ─────────────────────────────────────────────────────────────

const contract = parseYaml(read('bridge-contract.yaml'), { prettyErrors: true });

// Ensure output directories exist
const ktDir = resolve(ROOT, 'app/src/main/java/com/dopple/webview/bridge/generated');
mkdirSync(ktDir, { recursive: true });
mkdirSync(resolve(ROOT, 'sdk/generated'), { recursive: true });

// Generate and write files
const tsTypes = generateBridgeTypes(contract, 'public');
writeFileSync(resolve(ROOT, 'sdk/generated/bridge-types.ts'), tsTypes);
console.log('  ✓ sdk/generated/bridge-types.ts');

const internalTypes = generateBridgeTypes(contract, 'internal');
writeFileSync(resolve(ROOT, 'sdk/generated/internal-types.ts'), internalTypes);
console.log('  ✓ sdk/generated/internal-types.ts');

const ktContracts = generateKotlinContracts(contract);
writeFileSync(resolve(ktDir, 'BridgeContracts.kt'), ktContracts);
console.log('  ✓ app/.../generated/BridgeContracts.kt');

const snapshot = generateSnapshot(contract);
writeFileSync(resolve(ROOT, 'bridge-contract.snapshot.json'), snapshot);
console.log('  ✓ bridge-contract.snapshot.json');

console.log(`\nGenerated from bridge-contract.yaml v${contract.version}`);
