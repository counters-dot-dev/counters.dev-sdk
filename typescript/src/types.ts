// Types mirror openapi/openapi.yaml. Amounts and values are strings (arbitrary precision; never JS numbers).

import type { CountersError } from "./errors.js";

export type Amount = string; // non-negative integer, arbitrary precision
export type Value = string; // signed integer, arbitrary precision
/**
 * A signed, arbitrary-precision **decimal** value on the wire, always a string — never a JS `number`.
 * Distinct from `Value` (integer-only): the derived surface is decimal. A `DecimalValue` is `null`
 * when the expression divided by zero; the SDK surfaces that `null` (with a `reason`) rather than
 * throwing or coercing it to `"0"`, and never parses the string to a float (precision + fixed-scale loss).
 */
export type DecimalValue = string;
export type Granularity = "1m" | "5m" | "1h" | "1d" | "1w" | "1mo";
export type OperationType = "add" | "subtract" | "clear" | "delete";
/** Leaderboard aggregation mode. Set by the first member write to a board, then immutable. */
export type Mode = "sum" | "latest" | "min" | "max";
/** Whether ordinary writes may implicitly create a counter that has not been declared. */
export type UndeclaredCounterWrites = "allow" | "reject";
/** Trailing-window sizes for a windowed leaderboard read (`leaderboard?window=`). */
export type Window = "1h" | "6h" | "12h" | "1d" | "7d" | "30d";
/**
 * How to read each bucket's value in a member-dimensional series: `delta` (per-bucket change) on a
 * sum board, or the board mode (`min`/`max`/`latest` — bucket-best or bucket-latest) on a score board.
 */
export type MemberSeriesMode = "delta" | "min" | "max" | "latest";
/** Ranking direction. */
export type Order = "asc" | "desc";

export interface Counter {
  key: string;
  value: Value;
  epoch: number;
  /** Member-board aggregation mode, present on a configured counter detail read. */
  memberMode?: Mode;
  /** Whether per-member time series are currently enabled, present on a counter detail read. */
  memberSeriesEnabled?: boolean;
  /** Most recent disabled-to-enabled transition, retained across disable. */
  memberSeriesEnabledAt?: Date;
  /** Actor responsible for `memberSeriesEnabledAt`. */
  memberSeriesEnabledBy?: string;
  /** Current-epoch member count, present on a counter detail read. */
  memberCount?: number;
  createdAt?: Date;
  updatedAt?: Date;
}

/** Desired immutable settings for one counter in a bounded declaration request. */
export interface CounterDeclaration {
  key: string;
  memberMode?: Mode;
  memberSeriesEnabled?: boolean;
}

/** One result from a bulk declaration, in the same order as the request. */
export interface CounterDeclarationResult {
  key: string;
  status: "created" | "unchanged" | "error";
  epoch?: number;
  memberMode?: Mode;
  memberSeriesEnabled?: boolean;
  memberSeriesEnabledAt?: Date;
  memberSeriesEnabledBy?: string;
  memberCount?: number;
  error?: Problem;
}

/** Startup declaration request. The server returns one result per key and commits bounded batches. */
export interface DeclareCountersRequest {
  counters: CounterDeclaration[];
}

/** Declaration response; callers must inspect every per-key result. */
export interface DeclareCountersResponse {
  results: CounterDeclarationResult[];
  policy: CounterWritePolicy;
}

/** Versioned organization policy for implicit counter creation. */
export interface CounterWritePolicy {
  undeclaredCounterWrites: UndeclaredCounterWrites;
  version: number;
  explicit: boolean;
  updatedAt?: Date;
  updatedBy?: string;
}

/** Compare-and-set request for the organization policy. */
export interface SetCounterWritePolicyRequest {
  undeclaredCounterWrites: UndeclaredCounterWrites;
  expectedVersion: number;
}

/** Options for enabling or disabling per-member time series. */
export interface SetMemberSeriesOptions {
  /** Fail with 409 if the counter has been cleared to a different epoch. */
  expectedEpoch?: number;
}

/** Current per-member time-series configuration. */
export interface MemberSeriesConfig {
  key: string;
  enabled: boolean;
  memberCount: number;
  maxMembersWithSeries: number;
  mode?: Mode;
  /** Most recent disabled-to-enabled transition, retained across disable. */
  enabledAt?: Date;
  /** Actor responsible for `enabledAt`. */
  enabledBy?: string;
}

export interface ValueResponse {
  key: string;
  value: Value;
  epoch: number;
}

export interface CounterPage {
  data: Counter[];
  nextCursor?: string;
}

export interface SeriesPoint {
  /** Inclusive start of this bucket. */
  timestamp: Date;
  /** Arbitrary-precision delta for this bucket. */
  value: Value;
}

export interface SeriesResponse {
  counterKey: string;
  bucket: string;
  mode: "delta";
  /** IANA time zone used for calendar bucket boundaries. */
  timeZone?: string;
  range: { from: Date; to: Date };
  points: SeriesPoint[];
}

export interface Operation {
  counterKey: string;
  operation: OperationType;
  amount?: Amount;
  idempotencyKey?: string;
  /** Event time; buckets the operation at event time instead of ingest time (offline spools). */
  occurredAt?: Date;
}

/** Options shared by confirmed writes. Omit the key to have the SDK generate one. */
export interface WriteOptions {
  /**
   * Caller-supplied de-duplication key. Reuse only for the exact same operation and payload, and
   * only within the server's deduplication window.
   */
  idempotencyKey?: string;
}

/** Options for immediate (non-buffered) counter delta writes. */
export interface ApplyOptions extends WriteOptions {
  /** Event time for series bucketing; bounded server-side to the plan's retention window. */
  occurredAt?: Date;
}

export interface BatchResult {
  counterKey: string;
  status: "applied" | "deduplicated" | "error";
  value?: Value;
  error?: Problem;
}

export interface BatchResponse {
  results: BatchResult[];
}

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

export interface SeriesParams {
  from: Date;
  to: Date;
  bucket: Granularity;
  mode?: "delta";
  /** IANA time zone used for calendar bucket boundaries. */
  timeZone?: string;
  gapfill?: boolean;
}

/** A value accepted where an amount is expected. Normalised to a non-negative bigint internally. */
export type AmountInput = bigint | number | string;

/** A value accepted where a signed score is expected (member `submit`). Like {@link AmountInput}, but may be negative. */
export type ValueInput = bigint | number | string;

// ── Usage (GET /v1/usage) ─────────────────────────────────────────────────────────────────────────

/** Current quota state for the organization. `quota`/`monthlyOperationsQuota` are `null` on unlimited plans. */
export interface Usage {
  /** UTC month, e.g. "2026-07". */
  month: string;
  operations: {
    /** Write operations recorded this UTC month. */
    used: number;
    /** Monthly operation ceiling; `null` for unlimited plans. */
    quota: number | null;
    /** First instant of the next UTC month (present even when `quota` is null). */
    resetsAt: Date;
  };
  counters: {
    /** Live (non-deleted) counters in the org. */
    used: number;
    /** Counter cap for the plan. */
    max: number;
  };
  limits: {
    rateLimitRequestsPerSecond: number;
    maxCounters: number;
    /** Monthly operation ceiling; `null` for unlimited plans. */
    monthlyOperationsQuota: number | null;
  };
}

// ── Leaderboards (GET /v1/counters/{key}/leaderboard) ──────────────────────────────────────────────

/** Read parameters for a leaderboard page. */
export interface LeaderboardParams {
  limit?: number;
  offset?: number;
  order?: Order;
  /** Season to read; defaults to the counter's current epoch. */
  epoch?: number;
}

/** Read parameters for a windowed leaderboard (ranks trailing-window activity, not all-time standing). */
export interface WindowLeaderboardParams extends LeaderboardParams {
  window: Window;
}

export interface LeaderboardEntry {
  rank: number;
  member: string;
  value: Value;
  /** Opaque per-entry payload; present only when the entry carries it. */
  metadata?: string;
  updatedAt: Date;
}

export interface Leaderboard {
  key: string;
  mode: Mode;
  epoch: number;
  order: Order;
  /** The group total — present only on `sum` boards. */
  total?: Value;
  memberCount: number;
  limit: number;
  offset: number;
  entries: LeaderboardEntry[];
}

export interface WindowEntry {
  rank: number;
  member: string;
  value: Value;
}

/**
 * A windowed leaderboard: members ranked by their activity over the trailing window — the window-sum
 * on a `sum` board, the window-best (`min`/`max`) or window-latest (`latest`) value on a score board.
 * Members with no activity in the window are absent.
 */
export interface WindowLeaderboard {
  key: string;
  mode: Mode;
  window: Window;
  order: Order;
  /** The window group total — present only on `sum` boards (a sum of best lap times is nonsense). */
  total?: Value;
  memberCount: number;
  limit: number;
  offset: number;
  /** Effective lower bound actually summed (floored to the 1h rollup boundary; may precede `now − window`). */
  effectiveStart: Date;
  /** Effective upper bound actually summed (the request instant). */
  effectiveEnd: Date;
  entries: WindowEntry[];
}

// ── Members (…/members/{member}) ───────────────────────────────────────────────────────────────────

/** Options for an immediate member delta write (`add`/`subtract`). */
export interface MemberApplyOptions extends WriteOptions {
  /** Opaque payload (≤ 1024 UTF-8 bytes); stored and returned verbatim, riding accepted values only. */
  metadata?: string;
  /** Event time for series bucketing (see {@link ApplyOptions.occurredAt}). */
  occurredAt?: Date;
}

/** Options for a member score submit. */
export interface SubmitOptions extends MemberApplyOptions {
  /** Board mode; required on the first submit to an unconfigured board, ignored (and immutable) after. */
  mode?: Mode;
}

/**
 * One coalesced fire-and-forget write whose outcome failed or is unknown. `delta` is a signed,
 * arbitrary-precision decimal string: positive for an add and negative for a subtract.
 */
export interface WriteFailure {
  readonly counterKey: string;
  readonly delta: Value;
  /** Reserved for a member-attributed asynchronous write; current public buffers contain counter writes only. */
  readonly member?: string;
  /** The actual per-operation key sent in the failed/uncertain request. */
  readonly idempotencyKey: string;
  readonly error: CountersError;
}

/** Read parameters for a member snapshot. */
export interface MemberGetParams {
  /** Season to read; defaults to the counter's current epoch. */
  epoch?: number;
  order?: Order;
}

/** The standing value of a member after a write (add/subtract/submit). */
export interface MemberValue {
  key: string;
  member: string;
  memberValue: Value;
  /** False when a min/max submit kept the standing best (the rejected submission did not beat it). */
  memberAccepted: boolean;
  mode: Mode;
  epoch: number;
  /** The board total — present on `sum` boards. */
  value?: Value;
}

/** The result of removing a member from the current board. */
export interface MemberRemoved {
  key: string;
  member: string;
  epoch: number;
  /** The board total after removal — present on `sum` boards. */
  value?: Value;
}

/** A member's rank and percentile within its board. */
export interface MemberSnapshot {
  key: string;
  member: string;
  value: Value;
  metadata?: string;
  /** Competition rank (ties share; the next rank skips: 1, 2, 2, 4). */
  rank: number;
  /** Share of members at-or-behind, scale-2 string (e.g. "83.33"); leader and sole member read "100.00". Never a float. */
  percentile: string;
  memberCount: number;
  mode: Mode;
  epoch: number;
  updatedAt: Date;
}

// ── Dimensional member series (series?member= / series?groupBy=member) ──────────────────────────────

/**
 * One member's per-bucket series (`series?member=`). On a sum board each point is the bucket's
 * signed delta (`mode: "delta"`); on a score board each point is the bucket-best or bucket-latest
 * value and points are sparse — a missing bucket means "no submission", not zero.
 */
export interface MemberSeriesResponse {
  counterKey: string;
  member: string;
  bucket: string;
  mode: MemberSeriesMode;
  /** IANA time zone used for calendar bucket boundaries. */
  timeZone?: string;
  range: { from: Date; to: Date };
  points: SeriesPoint[];
}

export interface MemberSeriesEntry {
  member: string;
  points: SeriesPoint[];
}

/**
 * The per-member multi-series (`series?groupBy=member`): dense (gapfilled) on a sum board, sparse
 * per member on a score board (same rule as {@link MemberSeriesResponse} — a gap is "no submission").
 */
export interface MemberGroupSeriesResponse {
  counterKey: string;
  bucket: string;
  mode: MemberSeriesMode;
  /** IANA time zone used for calendar bucket boundaries. */
  timeZone?: string;
  range: { from: Date; to: Date };
  /** Number of members with activity in the requested range before selection. */
  memberCount: number;
  /** Number of real member series selected (excluding a synthetic `$other` series). */
  selectedCount: number;
  /** Whether selection omitted one or more real members. */
  truncated: boolean;
  series: MemberSeriesEntry[];
}

// ── Derived counters (GET /v1/derived/{key}/value|series) ──────────────────────────────────────────

/** The evaluated value of a derived counter (`null` on division by zero). */
export interface DerivedValueResponse {
  key: string;
  /** Signed decimal string, or `null` when the expression divided by zero (see `reason`). Never a float. */
  value: DecimalValue | null;
  /** Decimal places the value is rounded to (HALF_UP), fixed per definition. */
  scale: number;
  /** Each referenced counter's current integer value; a missing/deleted counter reads as "0". */
  inputs: Record<string, Value>;
  /** Present only when `value` is null (e.g. "division by zero"). */
  reason?: string;
}

export interface DerivedSeriesPoint {
  /** Inclusive start of this bucket. */
  timestamp: Date;
  /** Signed decimal string, or `null` for a bucket that divided by zero (a hole preserved in place). Never a float. */
  value: DecimalValue | null;
}

/** A derived counter evaluated per bucket over [from, to). Always dense. */
export interface DerivedSeriesResponse {
  key: string;
  bucket: string;
  /** IANA time zone used for calendar bucket boundaries. */
  timeZone?: string;
  scale: number;
  range: { from: Date; to: Date };
  points: DerivedSeriesPoint[];
}

/** Read parameters for a derived series. Only `from`/`to`/`bucket`/`timeZone` — no `gapfill`/`mode`/`member`/`groupBy`. */
export interface DerivedSeriesParams {
  from: Date;
  to: Date;
  bucket: Granularity;
  /** IANA time zone used for calendar bucket boundaries. */
  timeZone?: string;
}
