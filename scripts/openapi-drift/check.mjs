#!/usr/bin/env node
// OpenAPI ↔ hand-written SDK drift guard.
//
// The SDKs are deliberately hand-written, so nothing mechanically ties them to the spec at build
// time. This check closes the loop at CI time:
//   1. every operation in openapi/openapi.yaml must be either implemented by ALL three SDKs
//      (detected via per-operation source signatures) or explicitly allowlisted below;
//   2. no SDK may reference the keyless public endpoints;
//   3. an operation added to the spec without a mapping here fails the build, forcing a decision.
//
// Zero dependencies; run from the repo root: node scripts/openapi-drift/check.mjs

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const root = new URL("../..", import.meta.url).pathname;

// ---- 1. extract (method, path, operationId) triples from the spec ---------------------------------
const spec = readFileSync(join(root, "openapi/openapi.yaml"), "utf8").split("\n");
const operations = [];
let currentPath = null;
for (const line of spec) {
  const p = line.match(/^ {2}(\/[^\s:]*):\s*$/);
  if (p) currentPath = p[1];
  const m = line.match(/^ {4}(get|post|put|delete|patch):\s*$/);
  if (m && currentPath) operations.push({ path: currentPath, method: m[1], operationId: null });
  const id = line.match(/^ {6}operationId:\s*(\w+)\s*$/);
  if (id && operations.length > 0) operations[operations.length - 1].operationId = id[1];
}
if (operations.length === 0) fail("parsed zero operations from openapi/openapi.yaml — parser drift?");
for (const op of operations) {
  if (!op.operationId) fail(`operation ${op.method.toUpperCase()} ${op.path} has no operationId`);
}

// ---- 2. per-operation source signatures ------------------------------------------------------------
// An SDK "implements" an operation when its source matches every regex listed for it. Signatures are
// deliberately coarse (URL fragments + verbs) — they catch a removed/renamed endpoint, not subtle
// behavior drift (that is what conformance/http + the example-app E2E are for).
// Op segments may be a path literal ("/add") or a quoted op token passed to a shared helper ("add").
const SIGNATURES = {
  listCounters: [/\/counters\b/],
  deleteCounter: [/\/counters\b/, /delete/i],
  addToCounter: [/(\/add\b|["'`]add["'`])/],
  subtractFromCounter: [/(\/subtract\b|["'`]subtract["'`])/],
  clearCounter: [/(\/clear\b|["'`]clear["'`])/],
  getCounterValue: [/(\/value\b|["'`]value["'`])/],
  getCounterSeries: [/(\/series\b|["'`]series["'`])/],
  batchOperations: [/\/batch\b/],
  getUsage: [/\/usage\b/],
  getCounterLeaderboard: [/\/leaderboard\b/],
  getMember: [/\/members\//],
  removeMember: [/\/members\//, /delete/i],
  addToMember: [/\/members\//, /(\/add\b|["'`]add["'`])/],
  subtractFromMember: [/\/members\//, /(\/subtract\b|["'`]subtract["'`])/],
  submitMember: [/(\/submit\b|["'`]submit["'`])/],
  getDerivedValue: [/\/derived\//, /(\/value\b|["'`]value["'`])/],
  getDerivedSeries: [/\/derived\//, /(\/series\b|["'`]series["'`])/],
};

const PARAM_SIGNATURES = {
  getCounterSeries: [
    { name: "groupBy/group_by", signatures: [/groupBy|group_by/i] },
    { name: "member", signatures: [/\bmember\b/i] },
    { name: "mode", signatures: [/\bmode\b/i] },
  ],
  getCounterLeaderboard: [{ name: "window", signatures: [/window/i] }],
};

// Spec operations the SDKs intentionally do NOT implement. Adding an operationId here is a product
// decision — record why.
const NOT_SDK_SURFACE = {
  getCounter: "metadata GET duplicates value() today; revisit if the spec grows metadata-only fields",
  getDashboardReadCounter: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  getDashboardReadCounterLeaderboard: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  getDashboardReadCounterSeries: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  getDashboardReadCounterValues: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  getDashboardReadDerivedSeries: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  getDashboardReadDerivedValue: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  getDashboardReadMember: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  getDashboardReadUsage: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  listDashboardReadCounters: "dashboard-only direct-to-plane read surface — consumed by dashboard web, not machine SDKs",
  mintDashboardPlaneToken: "dashboard-only WorkOS-authenticated mint endpoint — consumed by dashboard web, not machine SDKs",
  getPublicCounterValue: "keyless public demo endpoint — website-only by design",
  tapPublicCounter: "keyless public demo endpoint — website-only by design",
};

// Fragments that must NOT appear in any SDK (would mean an SDK grew a public-endpoint client).
const FORBIDDEN = [/\/public\/counters/, /\/tap\b/];

const SDK_SOURCES = {
  typescript: ["typescript/src"],
  go: ["go"],
  java: ["java/src/main/java"],
};
const SOURCE_EXT = new Set([".ts", ".go", ".java"]);

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) yield* walk(full);
    else if (SOURCE_EXT.has(name.slice(name.lastIndexOf(".")))) yield full;
  }
}

function sdkSourceParts(lang) {
  const parts = [];
  for (const dir of SDK_SOURCES[lang]) {
    for (const file of walk(join(root, dir))) {
      // Test files under go's flat layout are not client surface.
      if (file.endsWith("_test.go")) continue;
      parts.push(readFileSync(file, "utf8"));
    }
  }
  return parts;
}

function paramHaystack(text) {
  // Param checks should be satisfied by operation-local param/query surface, not comments or
  // PascalCase response type names such as WindowLeaderboard.
  return text
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/\/\/.*$/gm, " ")
    .replace(/#[^\n]*/g, " ")
    .replace(/\b[A-Z][A-Za-z0-9_]*\b/g, " ");
}

function sourceWindows(parts, regexes) {
  const radius = 8000;
  const chunks = [];
  for (const text of parts) {
    for (const re of regexes) {
      const flags = re.flags.includes("g") ? re.flags : `${re.flags}g`;
      const global = new RegExp(re.source, flags);
      for (const match of text.matchAll(global)) {
        chunks.push(text.slice(Math.max(0, match.index - radius), match.index + match[0].length + radius));
      }
    }
  }
  return paramHaystack(chunks.join("\n"));
}

// ---- 3. run the checks -----------------------------------------------------------------------------
const errors = [];
const sourceParts = Object.fromEntries(Object.keys(SDK_SOURCES).map((l) => [l, sdkSourceParts(l)]));
const sources = Object.fromEntries(Object.entries(sourceParts).map(([l, parts]) => [l, parts.join("\n")]));

for (const op of operations) {
  if (op.operationId in NOT_SDK_SURFACE) continue;
  const sig = SIGNATURES[op.operationId];
  if (!sig) {
    errors.push(
      `spec operation '${op.operationId}' (${op.method.toUpperCase()} ${op.path}) has no drift signature ` +
        `and is not allowlisted — add it to scripts/openapi-drift/check.mjs (and to the SDKs, or to NOT_SDK_SURFACE)`,
    );
    continue;
  }
  for (const [lang, text] of Object.entries(sources)) {
    for (const re of sig) {
      if (!re.test(text)) {
        errors.push(`sdk '${lang}' does not implement '${op.operationId}': source does not match ${re}`);
      }
    }
    for (const param of PARAM_SIGNATURES[op.operationId] ?? []) {
      const opSource = sourceWindows(sourceParts[lang], sig);
      for (const re of param.signatures) {
        if (!re.test(opSource)) {
          errors.push(
            `sdk '${lang}' does not expose param '${param.name}' for '${op.operationId}': source does not match ${re}`,
          );
        }
      }
    }
  }
}

for (const [lang, text] of Object.entries(sources)) {
  for (const re of FORBIDDEN) {
    if (re.test(text)) {
      errors.push(`sdk '${lang}' references the keyless public surface (${re}) — website-only, not SDK surface`);
    }
  }
}

if (errors.length > 0) {
  console.error(`openapi-drift: ${errors.length} problem(s)\n`);
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}
console.log(`openapi-drift: OK — ${operations.length} spec operations checked against 3 SDKs`);

function fail(msg) {
  console.error(`openapi-drift: ${msg}`);
  process.exit(1);
}
