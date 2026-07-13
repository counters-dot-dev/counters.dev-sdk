#!/usr/bin/env node
// counters.dev TypeScript SDK — example app + end-to-end suite.
//
// This program is both living documentation and the E2E gate: it drives EVERY public method of the
// SDK against a real running server, asserts the outcomes, then replays the shared
// conformance/http vectors through the client. If a public method is not demonstrated here, the
// run fails — "if it isn't demonstrated, it isn't shipped."
//
// Env (see .github/actions/e2e-server): COUNTERS_BASE_URL (origin, no /v1), COUNTERS_API_KEY_A,
// COUNTERS_API_KEY_B, COUNTERS_PK_TOKEN (read-only token scoped to the fixed key "pk-demo").

import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  CounterHandle,
  CountersApiError,
  CountersClient,
  CountersError,
  CountersValidationError,
  DerivedHandle,
  MemberHandle,
} from "@counters.dev/sdk";

const ORIGIN = required("COUNTERS_BASE_URL").replace(/\/$/, "");
const KEY_A = required("COUNTERS_API_KEY_A");
const KEY_B = required("COUNTERS_API_KEY_B");
const PK_TOKEN = required("COUNTERS_PK_TOKEN");
const BASE_URL = `${ORIGIN}/v1`;

const ns = `e2e-ts-${Date.now().toString(36)}-`; // run-unique namespace: fresh counters, stable epochs
const t0 = new Date(Math.floor(Date.now() / 1000) * 1000);
const invoked = new Set();
let checks = 0;

function required(name) {
  const v = process.env[name];
  if (!v) {
    console.error(`missing required env: ${name}`);
    process.exit(2);
  }
  return v;
}

function assert(cond, what) {
  checks++;
  if (!cond) throw new Error(`assertion failed: ${what}`);
}

function assertEq(actual, expected, what) {
  assert(
    actual === expected,
    `${what}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
  );
}

async function expectStatus(promise, status, what) {
  try {
    await promise;
  } catch (e) {
    // B1/B2: an HTTP error response surfaces as CountersApiError (a CountersError subclass) carrying
    // the real status — never a transport error and never a synthetic status 0.
    if (e instanceof CountersApiError && e instanceof CountersError && e.status === status) return;
    throw new Error(`${what}: expected CountersApiError(${status}), got ${e}`);
  }
  throw new Error(`${what}: expected CountersApiError(${status}), but the call succeeded`);
}

// ── 1. The grand tour: every public method, the way an integrator would use it ──────────────────

async function tour() {
  const client = new CountersClient({
    apiKey: KEY_A,
    baseUrl: BASE_URL,
    timeoutMs: 15_000,
    batch: { intervalMs: 200, onError: (e) => console.error("batch flush failed:", e) },
  });
  invoked.add("CountersClient.constructor");

  // A typed handle per counter. Keys are validated client-side.
  const signups = client.counter(`${ns}signups`);
  invoked.add("CountersClient.counter");

  // Confirmed writes: apply immediately, return the new state.
  const first = await signups.addNow(5);
  invoked.add("CounterHandle.addNow");
  assertEq(first.value, "5", "addNow(5) on a fresh counter");
  assertEq(first.epoch, 0, "fresh counter epoch");

  const afterSub = await signups.subtractNow("2");
  invoked.add("CounterHandle.subtractNow");
  assertEq(afterSub.value, "3", "subtractNow(2)");

  // Fire-and-forget writes: buffered, coalesced per counter, flushed in the background.
  signups.add(4n);
  invoked.add("CounterHandle.add");
  signups.subtract(1);
  invoked.add("CounterHandle.subtract");
  await client.flush();
  invoked.add("CountersClient.flush");

  const current = await signups.value();
  invoked.add("CounterHandle.value");
  assertEq(current.value, "6", "value after confirmed + buffered writes (5-2+4-1)");

  // Event-time writes: occurredAt buckets the op into the past; totals are unaffected.
  await signups.addNow(10, { occurredAt: new Date(t0.getTime() - 2 * 3600_000) });
  assertEq((await signups.value()).value, "16", "total after an event-time write");

  // Series at every granularity the plan allows (pro: down to 1m). Sum == total delta.
  const from = new Date(t0.getTime() - 24 * 3600_000);
  const to = new Date(t0.getTime() + 24 * 3600_000);
  for (const bucket of ["1m", "5m", "1h", "1d", "1w", "1mo"]) {
    const series = await signups.series({ from, to, bucket });
    const sum = series.points.reduce((acc, p) => acc + BigInt(p.v), 0n);
    assertEq(sum.toString(), "16", `series(${bucket}) sums to the total delta`);
  }
  invoked.add("CounterHandle.series");

  // B6: an invalid bucket is rejected client-side (no request is sent) as a validation error.
  let badBucket = false;
  try {
    await signups.series({ from, to, bucket: "2m" });
  } catch (e) {
    badBucket = e instanceof CountersValidationError;
  }
  assertEq(String(badBucket), "true", "series() rejects a non-enum bucket client-side (B6)");

  // Arbitrary precision is the headline guarantee: 10^32 round-trips exactly.
  const big = client.counter(`${ns}big`);
  const HUGE = "100000000000000000000000000000000";
  await big.addNow(HUGE);
  await big.addNow(1);
  assertEq((await big.value()).value, "100000000000000000000000000000001", "10^32 + 1 round trip");
  await big.subtractNow(HUGE);
  assertEq((await big.value()).value, "1", "subtracting 10^32 back down");

  // list: pages in key order; follow nextCursor.
  for (const suffix of ["pg-a", "pg-b", "pg-c"]) await client.counter(ns + suffix).addNow(1);
  const seen = [];
  let cursor;
  do {
    const page = await client.list({ cursor, limit: 2 });
    seen.push(...page.data.map((c) => c.key));
    cursor = page.nextCursor;
  } while (cursor);
  invoked.add("CountersClient.list");
  const wantOrder = ["pg-a", "pg-b", "pg-c"].map((s) => ns + s);
  let matched = 0;
  for (const k of seen) if (matched < wantOrder.length && k === wantOrder[matched]) matched++;
  assertEq(matched, wantOrder.length, "list pagination walks all counters in key order");

  // clear: value back to 0 in a new epoch; history is retained.
  const cleared = await signups.clear();
  invoked.add("CounterHandle.clear");
  assertEq(cleared.value, "0", "clear resets to zero");
  assertEq(cleared.epoch, 1, "clear bumps the epoch");

  // delete: tombstoned; further use is a 404.
  const doomed = client.counter(`${ns}doomed`);
  await doomed.addNow(1);
  await doomed.delete();
  invoked.add("CounterHandle.delete");
  await expectStatus(doomed.value(), 404, "value after delete");
  await expectStatus(doomed.addNow(1), 404, "write after delete");

  // Tenant isolation: org B's key cannot see org A's counters.
  const clientB = new CountersClient({ apiKey: KEY_B, baseUrl: BASE_URL });
  await expectStatus(clientB.counter(`${ns}signups`).value(), 404, "cross-tenant read");
  await clientB.close();

  // Publishable tokens: read-only, scoped. The pk_ token is just the bearer key.
  const pkDemo = client.counter("pk-demo"); // fixed key the token is scoped to
  await pkDemo.addNow(1); // ensure it exists before clearing (first run on a fresh DB)
  await pkDemo.clear();
  await pkDemo.addNow(7);
  const pkClient = new CountersClient({ apiKey: PK_TOKEN, baseUrl: BASE_URL });
  assertEq((await pkClient.counter("pk-demo").value()).value, "7", "pk token reads its scoped counter");
  await pkClient.counter("pk-demo").series({ from, to, bucket: "1h" }); // read surface also includes series
  await expectStatus(pkClient.counter("pk-demo").addNow(1), 403, "pk token cannot write");
  await expectStatus(pkClient.counter(`${ns}signups`).value(), 403, "pk token cannot leave its scope");
  await expectStatus(pkClient.list(), 403, "pk token cannot list");
  await pkClient.close();

  // Usage: org-wide quota state. Tolerant lower-bound assertions — this org wrote many counters above.
  const usage = await client.usage();
  invoked.add("CountersClient.usage");
  assert(usage.ops.used >= 1, "usage reports at least the writes this run made");
  assert(usage.counters.used >= 1, "usage reports at least one live counter");
  assert(typeof usage.ops.resetsAt === "string" && usage.ops.resetsAt.length > 0, "usage carries a resetsAt instant");
  assert(typeof usage.month === "string", "usage carries the UTC month");

  await client.close();
  invoked.add("CountersClient.close");
}

// ── 1b. Leaderboards & members: the full board lifecycle against a live server ───────────────────

async function leaderboards() {
  const client = new CountersClient({ apiKey: KEY_A, baseUrl: BASE_URL });

  // ── Sum board: three members accumulate deltas; the board tracks ranks + a group total. ──
  const board = client.counter(`${ns}lb`);
  const alice = board.member("alice");
  invoked.add("CounterHandle.member");
  const bob = board.member("bob");
  const carol = board.member("carol");

  const a1 = await alice.add(10);
  invoked.add("MemberHandle.add");
  assertEq(a1.memberValue, "10", "alice member add");
  assertEq(a1.memberAccepted, true, "sum add is always accepted");
  assertEq(a1.mode, "sum", "first member add configures the board as sum");
  assertEq(a1.value, "10", "board total after alice");
  await bob.add(25);
  const c1 = await carol.add(10);
  assertEq(c1.value, "45", "board total after three members (10+25+10)");

  const lb = await board.leaderboard();
  invoked.add("CounterHandle.leaderboard");
  assertEq(lb.mode, "sum", "leaderboard mode is sum");
  assertEq(lb.order, "desc", "a sum board ranks highest-first by default");
  assertEq(lb.total, "45", "leaderboard total");
  assert(typeof lb.total === "string", "leaderboard total is a string (arbitrary precision)");
  assertEq(lb.memberCount, 3, "member count");
  assertEq(lb.entries[0].member, "bob", "rank 1 is bob");
  assertEq(lb.entries[0].rank, 1, "bob is rank 1");
  assertEq(lb.entries[0].value, "25", "bob value");
  assertEq(lb.entries[1].rank, 2, "alice/carol tie at rank 2 (competition rank)");
  assertEq(lb.entries[2].rank, 2, "the tie shares rank 2");

  // Immediate member subtract (the counter may go negative; here it just draws down).
  const aSub = await alice.subtract(5);
  invoked.add("MemberHandle.subtract");
  assertEq(aSub.memberValue, "5", "alice value after subtracting 5");
  assertEq(aSub.value, "40", "board total after the subtract");

  // Member snapshot: rank + percentile (a scale-2 STRING; the leader reads "100.00").
  const snap = await bob.get();
  invoked.add("MemberHandle.get");
  assertEq(snap.rank, 1, "bob snapshot rank");
  assertEq(snap.value, "25", "bob snapshot value");
  assertEq(snap.percentile, "100.00", "the leader's percentile is 100.00");
  assert(typeof snap.percentile === "string", "percentile stays a string");

  // Remove a member: a sum board compensates the removed value out of the group total.
  const removed = await carol.remove();
  invoked.add("MemberHandle.remove");
  assertEq(removed.value, "30", "board total after removing carol (40 − 10)");
  const lb2 = await board.leaderboard();
  assertEq(lb2.memberCount, 2, "member count after removal");

  // Windowed read requires member series enabled (a dashboard-plane toggle these keys don't have):
  // the typed ApiError carries the real 400.
  await expectStatus(board.leaderboard({ window: "7d" }), 400, "windowed leaderboard without member series enabled");

  // ── Score board (min): keep-best submits; a worse submit is a successful, un-accepted call. ──
  const raid = client.counter(`${ns}raid`);
  const team = raid.member("alice|bob|carol"); // a composite member key ( '|' is legal, percent-encoded)
  const s1 = await team.submit(1502, { mode: "min", metadata: "room1:500" });
  invoked.add("MemberHandle.submit");
  assertEq(s1.memberValue, "1502", "first min submit stands");
  assertEq(s1.memberAccepted, true, "first submit accepted");
  assertEq(s1.mode, "min", "first submit configures the board as min");
  const s2 = await team.submit(1417, { mode: "min", metadata: "room1:400" });
  assertEq(s2.memberValue, "1417", "a better (lower) min is kept");
  assertEq(s2.memberAccepted, true, "the improving submit is accepted");
  const s3 = await team.submit(1600, { mode: "min" });
  assertEq(s3.memberValue, "1417", "a worse submit keeps the standing best");
  assertEq(s3.memberAccepted, false, "the worse submit is recorded but not accepted");

  const teamSnap = await team.get();
  assertEq(teamSnap.value, "1417", "kept-best value in the snapshot");
  assertEq(teamSnap.metadata, "room1:400", "metadata rode the accepted submit");

  await raid.member("dan").submit(1300, { mode: "min" });
  const raidLb = await raid.leaderboard();
  assertEq(raidLb.mode, "min", "raid board mode is min");
  assertEq(raidLb.order, "asc", "a min board ranks lowest-first by default");
  assertEq(raidLb.entries[0].member, "dan", "dan (1300) is the best min");
  assertEq(raidLb.entries[0].value, "1300", "dan value");
  assertEq(raidLb.entries[1].member, "alice|bob|carol", "the team is rank 2");
  assertEq(raidLb.entries[1].metadata, "room1:400", "the entry carries the accepted metadata");

  await client.close();
}

// ── 1c. Derived counters: read wiring + error mapping (definitions are dashboard-only) ───────────

async function derived() {
  const client = new CountersClient({ apiKey: KEY_A, baseUrl: BASE_URL });
  const d = client.derived(`${ns}conversion`);
  invoked.add("CountersClient.derived");
  // No definition exists (definitions live on the dashboard/JWT plane), so both reads map to a typed
  // 404 — proving the request wiring, the DerivedHandle surface, and error mapping end to end.
  await expectStatus(d.value(), 404, "derived value with no definition");
  invoked.add("DerivedHandle.value");
  await expectStatus(
    d.series({ from: new Date(t0.getTime() - 3600_000), to: t0, bucket: "1h" }),
    404,
    "derived series with no definition",
  );
  invoked.add("DerivedHandle.series");
  await client.close();
}

// ── 2. Shared conformance vectors, replayed through the real client ─────────────────────────────

function loadCases() {
  // examples/e2e -> examples -> typescript -> repo root (same walk the unit-test helpers use)
  const root = join(new URL(".", import.meta.url).pathname, "..", "..", "..");
  return JSON.parse(readFileSync(join(root, "conformance", "http", "cases.json"), "utf8")).cases;
}

async function replayVectors() {
  const clients = new Map([
    ["A", new CountersClient({ apiKey: KEY_A, baseUrl: BASE_URL })],
    ["B", new CountersClient({ apiKey: KEY_B, baseUrl: BASE_URL })],
  ]);
  const minutes = (n) => new Date(t0.getTime() + n * 60_000);
  const memberOpts = (op) => {
    const opts = {};
    if (op.metadata !== undefined) opts.metadata = op.metadata;
    if (op.occurredAtMin !== undefined) opts.occurredAt = minutes(op.occurredAtMin);
    return opts;
  };
  const cases = loadCases().filter((c) => c.scope === "all");
  assert(cases.length >= 10, `expected a healthy scope:all vector count, got ${cases.length}`);

  for (const [i, c] of cases.entries()) {
    const prefix = `${ns}c${i}-`;
    for (const [s, step] of c.steps.entries()) {
      const op = step.do;
      const expect = step.expect;
      const client = clients.get(op.org);
      const handle = op.key ? client.counter(prefix + op.key) : undefined;
      const opts = op.occurredAtMin !== undefined ? { occurredAt: minutes(op.occurredAtMin) } : undefined;

      const run = async () => {
        switch (op.op) {
          case "add":
            return handle.addNow(op.amount, opts);
          case "subtract":
            return handle.subtractNow(op.amount, opts);
          case "clear":
            return handle.clear();
          case "delete":
            return handle.delete();
          case "value":
            return handle.value();
          case "series": {
            const p = op.series;
            return handle.series({
              from: minutes(p.fromMin),
              to: minutes(p.toMin),
              bucket: p.bucket,
              tz: p.tz,
              gapfill: p.gapfill,
            });
          }
          case "list": {
            const walked = [];
            let cursor;
            do {
              const page = await client.list({ cursor, limit: op.list?.limit ?? 50 });
              walked.push(...page.data.map((x) => x.key));
              cursor = page.nextCursor;
            } while (cursor);
            return { walked };
          }
          case "usage":
            return client.usage();
          // Leaderboard/member ops. These vectors are scope:"http" today; the SDK implements the
          // surface, so if they are re-scoped to "all" this runner replays them unchanged.
          // Member keys are literal (not namespaced) — they live inside a board.
          case "memberAdd":
            return handle.member(op.member).add(op.amount, memberOpts(op));
          case "memberSubtract":
            return handle.member(op.member).subtract(op.amount, memberOpts(op));
          case "memberSubmit":
            return handle.member(op.member).submit(op.value, { mode: op.mode, ...memberOpts(op) });
          case "memberGet":
            return handle.member(op.member).get({ epoch: op.epoch, order: op.order });
          case "memberRemove":
            return handle.member(op.member).remove();
          case "leaderboard":
            return handle.leaderboard({
              limit: op.limit,
              offset: op.offset,
              order: op.order,
              epoch: op.epoch,
              window: op.window,
            });
          default:
            throw new Error(`vector op '${op.op}' is not part of the SDK surface (case should be scope: http)`);
        }
      };

      const where = `${c.name} step ${s}`;
      if (expect.status < 200 || expect.status >= 300) {
        await expectStatus(run(), expect.status, where);
        continue;
      }
      const body = await run().catch((e) => {
        throw new Error(`${where}: expected success, got ${e}`);
      });
      if (expect.key !== undefined) assertEq(body.key, prefix + expect.key, `${where}: key`);
      if (expect.value !== undefined) assertEq(body.value, expect.value, `${where}: value`);
      if (expect.epoch !== undefined) assertEq(body.epoch, expect.epoch, `${where}: epoch`);
      if (expect.pointsSum !== undefined) {
        const sum = body.points.reduce((acc, p) => acc + BigInt(p.v), 0n);
        assertEq(sum.toString(), expect.pointsSum, `${where}: pointsSum`);
      }
      if (expect.pointsAtLeast !== undefined) {
        assert(body.points.length >= expect.pointsAtLeast, `${where}: pointsAtLeast ${expect.pointsAtLeast}, got ${body.points.length}`);
      }
      if (expect.containsInOrder !== undefined) {
        const want = expect.containsInOrder.map((k) => prefix + k);
        let m = 0;
        for (const k of body.walked) if (m < want.length && k === want[m]) m++;
        assertEq(m, want.length, `${where}: containsInOrder`);
      }
      if (expect.usage !== undefined) {
        const u = expect.usage;
        if (u.opsUsedAtLeast !== undefined) assert(body.ops.used >= u.opsUsedAtLeast, `${where}: opsUsedAtLeast ${u.opsUsedAtLeast}, got ${body.ops.used}`);
        if (u.countersUsedAtLeast !== undefined) assert(body.counters.used >= u.countersUsedAtLeast, `${where}: countersUsedAtLeast ${u.countersUsedAtLeast}, got ${body.counters.used}`);
        if (u.hasResetsAt !== undefined) assertEq(body.ops.resetsAt !== undefined && body.ops.resetsAt !== null, u.hasResetsAt, `${where}: hasResetsAt`);
      }
      // Leaderboard/member expectations (member keys are literal — not namespaced).
      if (expect.memberValue !== undefined) assertEq(body.memberValue, expect.memberValue, `${where}: memberValue`);
      if (expect.memberAccepted !== undefined) assertEq(body.memberAccepted, expect.memberAccepted, `${where}: memberAccepted`);
      if (expect.mode !== undefined) assertEq(body.mode, expect.mode, `${where}: mode`);
      if (expect.order !== undefined) assertEq(body.order, expect.order, `${where}: order`);
      if (expect.total !== undefined) assertEq(body.total, expect.total, `${where}: total`);
      if (expect.memberCount !== undefined) assertEq(body.memberCount, expect.memberCount, `${where}: memberCount`);
      if (expect.rank !== undefined) assertEq(body.rank, expect.rank, `${where}: rank`);
      if (expect.percentile !== undefined) assertEq(body.percentile, expect.percentile, `${where}: percentile`);
      if (expect.metadata !== undefined) assertEq(body.metadata, expect.metadata, `${where}: metadata`);
      if (expect.entries !== undefined) {
        assertEq(body.entries.length, expect.entries.length, `${where}: entries length`);
        expect.entries.forEach((we, i) => {
          const ge = body.entries[i];
          if (we.rank !== undefined) assertEq(ge.rank, we.rank, `${where}: entry ${i} rank`);
          if (we.member !== undefined) assertEq(ge.member, we.member, `${where}: entry ${i} member`);
          if (we.value !== undefined) assertEq(ge.value, we.value, `${where}: entry ${i} value`);
          if (we.metadata !== undefined) assertEq(ge.metadata, we.metadata, `${where}: entry ${i} metadata`);
        });
      }
    }
    console.log(`  ok   vector: ${c.name}`);
  }
  for (const client of clients.values()) await client.close();
}

// ── 3. Surface-completeness gate: no public method may go undemonstrated ────────────────────────

function surfaceGate() {
  const documented = {
    CountersClient: ["counter", "list", "usage", "derived", "flush", "close"],
    CounterHandle: ["add", "subtract", "addNow", "subtractNow", "clear", "delete", "value", "series", "leaderboard", "member"],
    MemberHandle: ["get", "remove", "add", "subtract", "submit"],
    DerivedHandle: ["value", "series"],
  };
  // TS `private`/@internal members still exist on the prototype at runtime; they are not SDK surface.
  const internals = {
    CountersClient: new Set([
      "constructor", "enqueue", "addNow", "subtractNow", "clearCounter", "deleteCounter", "getValue",
      "getSeries", "fireSingle", "batcherOnError", "submitBatch", "getLeaderboard", "getMember",
      "removeMember", "addToMember", "subtractFromMember", "submitMember", "memberDelta",
      "getDerivedValue", "getDerivedSeries",
    ]),
    CounterHandle: new Set(["constructor"]),
    MemberHandle: new Set(["constructor"]),
    DerivedHandle: new Set(["constructor"]),
  };
  for (const [name, proto] of [
    ["CountersClient", CountersClient.prototype],
    ["CounterHandle", CounterHandle.prototype],
    ["MemberHandle", MemberHandle.prototype],
    ["DerivedHandle", DerivedHandle.prototype],
  ]) {
    for (const method of documented[name]) {
      assert(
        invoked.has(`${name}.${method}`),
        `public method ${name}.${method} was never demonstrated by this example app`,
      );
    }
    for (const prop of Object.getOwnPropertyNames(proto)) {
      if (internals[name].has(prop) || documented[name].includes(prop)) continue;
      throw new Error(
        `${name}.${prop} is a new public prototype member not covered by the example app — ` +
          `demonstrate it here (and add it to 'documented') or mark it internal`,
      );
    }
  }
  assert(invoked.has("CountersClient.constructor"), "constructor demonstrated");
}

// ── main ─────────────────────────────────────────────────────────────────────────────────────────

try {
  console.log(`counters.dev TS SDK e2e — ${BASE_URL} (ns ${ns})`);
  await tour();
  console.log("  ok   full public-surface tour");
  await leaderboards();
  console.log("  ok   leaderboards + members lifecycle");
  await derived();
  console.log("  ok   derived-counter read wiring");
  await replayVectors();
  surfaceGate();
  console.log(`\nPASS — entire public SDK surface + shared vectors verified against a live server (${checks} assertions)`);
} catch (e) {
  console.error(`\nFAIL — ${e.message ?? e}`);
  process.exit(1);
}
