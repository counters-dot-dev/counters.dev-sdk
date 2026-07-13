// Types mirror openapi/openapi.yaml. Amounts and values are strings (arbitrary precision; never JS numbers).

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
export type OpType = "add" | "subtract" | "clear" | "delete";
/** Leaderboard aggregation mode. Set by the first member write to a board, then immutable. */
export type Mode = "sum" | "latest" | "min" | "max";
/** Trailing-window sizes for a windowed leaderboard read (`leaderboard?window=`). */
export type Window = "1h" | "6h" | "12h" | "1d" | "7d" | "30d";
/** Ranking direction. */
export type Order = "asc" | "desc";

export interface Counter {
  key: string;
  value: Value;
  epoch: number;
  createdAt?: string;
  updatedAt?: string;
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
  t: string;
  v: Value;
}

export interface SeriesResponse {
  counterKey: string;
  bucket: string;
  mode: "delta";
  tz?: string;
  range: { from: string; to: string };
  points: SeriesPoint[];
}

export interface Operation {
  counterKey: string;
  op: OpType;
  amount?: Amount;
  idempotencyKey?: string;
  /** RFC 3339 event time; buckets the op at event time instead of ingest time (offline spools). */
  occurredAt?: string;
}

/** Options for immediate (non-buffered) writes. */
export interface ApplyOptions {
  /** Event time for series bucketing; bounded server-side to the plan's retention window. */
  occurredAt?: string | Date;
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
  from: string | Date;
  to: string | Date;
  bucket: Granularity;
  mode?: "delta";
  tz?: string;
  gapfill?: boolean;
}

/** A value accepted where an amount is expected. Normalised to a non-negative bigint internally. */
export type AmountInput = bigint | number | string;

/** A value accepted where a signed score is expected (member `submit`). Like {@link AmountInput}, but may be negative. */
export type ValueInput = bigint | number | string;

// ── Usage (GET /v1/usage) ─────────────────────────────────────────────────────────────────────────

/** Current quota state for the organization. `quota`/`monthlyOpsQuota` are `null` on unlimited plans. */
export interface Usage {
  /** UTC month, e.g. "2026-07". */
  month: string;
  ops: {
    /** Write ops recorded this UTC month. */
    used: number;
    /** Monthly op ceiling; `null` for unlimited plans. */
    quota: number | null;
    /** First instant of the next UTC month (present even when `quota` is null). */
    resetsAt: string;
  };
  counters: {
    /** Live (non-deleted) counters in the org. */
    used: number;
    /** Counter cap for the plan. */
    max: number;
  };
  limits: {
    rateLimitRps: number;
    maxCounters: number;
    /** Monthly op ceiling; `null` for unlimited plans. */
    monthlyOpsQuota: number | null;
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
  updatedAt: string;
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

/** A windowed leaderboard: always a `sum` board, ranking summed activity over the trailing window. */
export interface WindowLeaderboard {
  key: string;
  mode: "sum";
  window: Window;
  order: Order;
  total: Value;
  memberCount: number;
  limit: number;
  offset: number;
  /** Effective lower bound actually summed (floored to the 1h rollup boundary; may precede `now − window`). */
  effectiveStart: string;
  /** Effective upper bound actually summed (the request instant). */
  effectiveEnd: string;
  entries: WindowEntry[];
}

// ── Members (…/members/{member}) ───────────────────────────────────────────────────────────────────

/** Options for an immediate member delta write (`add`/`subtract`). */
export interface MemberApplyOptions {
  /** Opaque payload (≤ 1024 UTF-8 bytes); stored and returned verbatim, riding accepted values only. */
  metadata?: string;
  /** Event time for series bucketing (see {@link ApplyOptions.occurredAt}). */
  occurredAt?: string | Date;
}

/** Options for a member score submit. */
export interface SubmitOptions extends MemberApplyOptions {
  /** Board mode; required on the first submit to an unconfigured board, ignored (and immutable) after. */
  mode?: Mode;
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
  updatedAt: string;
}

// ── Dimensional member series (series?member= / series?groupBy=member) ──────────────────────────────

/** One member's per-bucket delta series (`series?member=`). */
export interface MemberSeriesResponse {
  counterKey: string;
  member: string;
  bucket: string;
  mode: "delta";
  tz?: string;
  range: { from: string; to: string };
  points: SeriesPoint[];
}

export interface MemberSeriesEntry {
  member: string;
  points: SeriesPoint[];
}

/** The dense per-member multi-series (`series?groupBy=member`). No top-level `mode`. */
export interface MemberGroupSeriesResponse {
  counterKey: string;
  bucket: string;
  tz?: string;
  range: { from: string; to: string };
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
  t: string;
  /** Signed decimal string, or `null` for a bucket that divided by zero (a hole preserved in place). Never a float. */
  v: DecimalValue | null;
}

/** A derived counter evaluated per bucket over [from, to). Always dense. */
export interface DerivedSeriesResponse {
  key: string;
  bucket: string;
  tz?: string;
  scale: number;
  range: { from: string; to: string };
  points: DerivedSeriesPoint[];
}

/** Read parameters for a derived series. Only `from`/`to`/`bucket`/`tz` — no `gapfill`/`mode`/`member`/`groupBy`. */
export interface DerivedSeriesParams {
  from: string | Date;
  to: string | Date;
  bucket: Granularity;
  tz?: string;
}
