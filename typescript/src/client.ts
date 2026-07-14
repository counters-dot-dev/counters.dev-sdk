import { Batcher } from "./batcher.js";
import {
  CountersApiError,
  CountersError,
  CountersTransportError,
  CountersValidationError,
} from "./errors.js";
import { Http } from "./http.js";
import { newIdempotencyKey } from "./idempotency.js";
import {
  assertBucket,
  assertCounterKey,
  assertMemberKey,
  assertMetadata,
  assertWindow,
  toAmount,
  toValue,
} from "./validation.js";
import type {
  AmountInput,
  ApplyOptions,
  BatchResponse,
  Counter,
  CounterPage,
  DerivedSeriesParams,
  DerivedSeriesResponse,
  DerivedValueResponse,
  Leaderboard,
  LeaderboardEntry,
  LeaderboardParams,
  MemberApplyOptions,
  MemberGetParams,
  MemberGroupSeriesResponse,
  MemberRemoved,
  MemberSeriesEntry,
  MemberSeriesResponse,
  MemberSnapshot,
  MemberValue,
  Operation,
  SeriesParams,
  SeriesPoint,
  SeriesResponse,
  SubmitOptions,
  Usage,
  Value,
  ValueInput,
  ValueResponse,
  Window,
  WindowLeaderboard,
  WindowLeaderboardParams,
} from "./types.js";

export interface CountersClientOptions {
  apiKey: string;
  baseUrl?: string;
  fetch?: typeof fetch;
  maxRetries?: number;
  backoffMs?: number;
  /** Per-attempt request timeout in milliseconds (default 30s). */
  timeoutMs?: number;
  batch?: {
    /** Buffer + coalesce `add`/`subtract` writes (default true). When false, each write fires immediately. */
    enabled?: boolean;
    /** Buffered distinct-counter count that triggers an early flush (default 100). */
    maxBatchSize?: number;
    /** Background flush interval in milliseconds (default 1000). `<= 0` disables the timer. */
    intervalMs?: number;
    /**
     * Sink for errors from fire-and-forget writes — background flushes and, when `enabled` is false,
     * immediate-mode writes. These run detached from any caller, so without this hook they are silent.
     */
    onError?: (e: CountersError) => void;
  };
}

/**
 * Options for a scoped, read-only publishable-token client. Publishable clients never buffer writes,
 * so the writable client's `batch` configuration is intentionally absent.
 */
export type PublishableCountersClientOptions = Omit<CountersClientOptions, "batch"> & {
  /** Publishable clients have no write buffer to configure. */
  batch?: never;
};

const DEFAULT_BASE_URL = "https://api.counters.dev/v1";

export class CountersClient {
  private readonly http: Http;
  private readonly batcher: Batcher;
  private readonly batchEnabled: boolean;
  private readonly onWriteError?: (e: CountersError) => void;

  constructor(opts: CountersClientOptions) {
    if (!opts.apiKey) throw new Error("CountersClient: apiKey is required");
    this.http = new Http({
      baseUrl: opts.baseUrl ?? DEFAULT_BASE_URL,
      apiKey: opts.apiKey,
      fetch: opts.fetch,
      maxRetries: opts.maxRetries,
      backoffMs: opts.backoffMs,
      timeoutMs: opts.timeoutMs,
    });
    this.batchEnabled = opts.batch?.enabled ?? true;
    this.onWriteError = opts.batch?.onError;
    this.batcher = new Batcher((ops) => this.submitBatch(ops), {
      maxBatchSize: opts.batch?.maxBatchSize ?? 100,
      intervalMs: opts.batch?.intervalMs ?? 1000,
      onError: opts.batch?.onError,
    });
  }

  /** Get a handle for a counter. Throws CountersValidationError if the key is invalid. */
  counter(key: string): CounterHandle {
    assertCounterKey(key);
    return new CounterHandle(this, key);
  }

  /** List counters in the organization. */
  list(opts: { cursor?: string; limit?: number } = {}): Promise<CounterPage> {
    return this.http
      .request<WireCounterPage>("GET", "/counters", {
        query: { cursor: opts.cursor, limit: opts.limit },
      })
      .then(parseCounterPage);
  }

  /** Current quota state for the organization (month-to-date ops, counter headroom, plan limits). */
  usage(): Promise<Usage> {
    return this.http.request<Usage>("GET", "/usage");
  }

  /**
   * Get a handle for a derived counter — a server-defined, read-only decimal expression over the
   * org's counters. Throws CountersValidationError if the key is invalid (same shape as a counter key).
   */
  derived(key: string): DerivedHandle {
    assertCounterKey(key);
    return new DerivedHandle(this, key);
  }

  /** Flush any buffered operations now. */
  flush(): Promise<void> {
    return this.batcher.flush();
  }

  /** Flush and stop the background timer. Call before process exit to avoid losing buffered writes. */
  close(): Promise<void> {
    return this.batcher.close();
  }

  // ---- internals used by CounterHandle ----

  /** @internal */
  enqueue(key: string, delta: bigint): void {
    if (this.batchEnabled) this.batcher.enqueue(key, delta);
    else this.fireSingle(key, delta);
  }

  /** @internal */
  addNow(key: string, amount: bigint, opts?: ApplyOptions): Promise<Counter> {
    return this.http
      .request<WireCounter>("POST", `/counters/${enc(key)}/add`, {
        body: applyBody(amount, opts),
        idempotencyKey: newIdempotencyKey(),
      })
      .then(parseCounter);
  }

  /** @internal */
  subtractNow(key: string, amount: bigint, opts?: ApplyOptions): Promise<Counter> {
    return this.http
      .request<WireCounter>("POST", `/counters/${enc(key)}/subtract`, {
        body: applyBody(amount, opts),
        idempotencyKey: newIdempotencyKey(),
      })
      .then(parseCounter);
  }

  /** @internal */
  clearCounter(key: string): Promise<Counter> {
    return this.http
      .request<WireCounter>("POST", `/counters/${enc(key)}/clear`, {
        idempotencyKey: newIdempotencyKey(),
      })
      .then(parseCounter);
  }

  /** @internal */
  deleteCounter(key: string): Promise<void> {
    return this.http.request<void>("DELETE", `/counters/${enc(key)}`, {
      idempotencyKey: newIdempotencyKey(),
    });
  }

  /** @internal */
  getValue(key: string): Promise<ValueResponse> {
    return this.http.request<ValueResponse>("GET", `/counters/${enc(key)}/value`);
  }

  /** @internal */
  getSeries(
    key: string,
    params: SeriesParams & { member?: string; groupBy?: "member" },
  ): Promise<SeriesResponse | MemberSeriesResponse | MemberGroupSeriesResponse> {
    assertBucket(params.bucket);
    // `member` and `groupBy` are mutually exclusive (the server answers 400) — reject the
    // combination client-side before any network I/O (conformance/series member-and-groupby case).
    if (params.member !== undefined && params.groupBy !== undefined) {
      throw new CountersValidationError(
        "series: `member` and `groupBy` are mutually exclusive — set at most one",
      );
    }
    if (params.member !== undefined) assertMemberKey(params.member);
    return this.http
      .request<WireSeriesResponse | WireMemberSeriesResponse | WireMemberGroupSeriesResponse>(
        "GET",
        `/counters/${enc(key)}/series`,
        {
          query: {
            from: toIso(params.from),
            to: toIso(params.to),
            bucket: params.bucket,
            mode: params.mode,
            tz: params.tz,
            // gapfill: omit-when-false. The spec default is already `false`, so an explicit
            // `gapfill=false` is identical to omission — send the parameter only when `true`, matching
            // the other SDKs (pinned by the conformance/series query-encoding vectors).
            gapfill: params.gapfill ? true : undefined,
            // Dimensional variants: `member` selects one member's series, `groupBy=member` the dense
            // per-member multi-series. Passed through verbatim under the same presence-exact contract.
            member: params.member,
            groupBy: params.groupBy,
          },
        },
      )
      .then(parseSeriesResult);
  }

  /** @internal */
  getLeaderboard(
    key: string,
    params: LeaderboardParams & { window?: Window },
  ): Promise<Leaderboard | WindowLeaderboard> {
    if (params.window !== undefined) assertWindow(params.window);
    const path = `/counters/${enc(key)}/leaderboard`;
    const opts = {
      query: {
        limit: params.limit,
        offset: params.offset,
        order: params.order,
        epoch: params.epoch,
        window: params.window,
      },
    };
    if (params.window !== undefined) {
      return this.http.request<WindowLeaderboard>("GET", path, opts);
    }
    return this.http.request<WireLeaderboard>("GET", path, opts).then(parseLeaderboard);
  }

  /** @internal */
  getMember(key: string, member: string, params?: MemberGetParams): Promise<MemberSnapshot> {
    return this.http
      .request<WireMemberSnapshot>("GET", `/counters/${enc(key)}/members/${enc(member)}`, {
        query: { epoch: params?.epoch, order: params?.order },
      })
      .then(parseMemberSnapshot);
  }

  /** @internal */
  removeMember(key: string, member: string): Promise<MemberRemoved> {
    return this.http.request<MemberRemoved>(
      "DELETE",
      `/counters/${enc(key)}/members/${enc(member)}`,
      { idempotencyKey: newIdempotencyKey() },
    );
  }

  /** @internal */
  addToMember(
    key: string,
    member: string,
    amount: bigint,
    opts?: MemberApplyOptions,
  ): Promise<MemberValue> {
    return this.memberDelta("add", key, member, amount, opts);
  }

  /** @internal */
  subtractFromMember(
    key: string,
    member: string,
    amount: bigint,
    opts?: MemberApplyOptions,
  ): Promise<MemberValue> {
    return this.memberDelta("subtract", key, member, amount, opts);
  }

  /** @internal */
  submitMember(
    key: string,
    member: string,
    value: bigint,
    opts?: SubmitOptions,
  ): Promise<MemberValue> {
    return this.http.request<MemberValue>(
      "POST",
      `/counters/${enc(key)}/members/${enc(member)}/submit`,
      { body: submitBody(value, opts), idempotencyKey: newIdempotencyKey() },
    );
  }

  private memberDelta(
    op: "add" | "subtract",
    key: string,
    member: string,
    amount: bigint,
    opts?: MemberApplyOptions,
  ): Promise<MemberValue> {
    return this.http.request<MemberValue>(
      "POST",
      `/counters/${enc(key)}/members/${enc(member)}/${op}`,
      { body: memberAmountBody(amount, opts), idempotencyKey: newIdempotencyKey() },
    );
  }

  /** @internal */
  getDerivedValue(key: string): Promise<DerivedValueResponse> {
    return this.http.request<DerivedValueResponse>("GET", `/derived/${enc(key)}/value`);
  }

  /** @internal */
  getDerivedSeries(key: string, params: DerivedSeriesParams): Promise<DerivedSeriesResponse> {
    assertBucket(params.bucket);
    return this.http.request<DerivedSeriesResponse>("GET", `/derived/${enc(key)}/series`, {
      query: {
        from: toIso(params.from),
        to: toIso(params.to),
        bucket: params.bucket,
        tz: params.tz,
      },
    });
  }

  private fireSingle(key: string, delta: bigint): void {
    // Match the buffered path: a write after close() has no worker to observe it — surface the misuse.
    if (this.batcher.isClosed()) throw new CountersError("cannot enqueue on a closed client");
    const op: Operation =
      delta >= 0n
        ? { counterKey: key, op: "add", amount: delta.toString(), idempotencyKey: newIdempotencyKey() }
        : { counterKey: key, op: "subtract", amount: (-delta).toString(), idempotencyKey: newIdempotencyKey() };
    // Fire-and-forget, like a background flush — so failures route to the same onError sink
    // (previously they were swallowed, which silently dropped counted writes).
    void this.submitBatch([op]).catch((e) => this.onWriteError?.(normaliseWriteError(e)));
  }

  private submitBatch(ops: Operation[]): Promise<void> {
    return this.http.request<BatchResponse>("POST", "/batch", { body: { operations: ops } }).then((res) => {
      // The HTTP 200 only means the batch was accepted; each operation carries its own status. A
      // per-op "error" (e.g. a counter/quota cap) would otherwise vanish silently — surface it so the
      // buffered path routes it to onError and the immediate path can observe it.
      const failed = (res.results ?? []).filter((r) => r.status === "error");
      if (failed.length > 0) {
        const first = failed[0]!;
        const msg = `batch: ${failed.length}/${res.results.length} operation(s) failed (${first.counterKey}: ${first.error?.title ?? "error"})`;
        // Per-op error mapping. A problem carrying a `status`
        // surfaces as the api type — exactly as if the operation had failed standalone. A problem with
        // no status (or no problem at all) is a response the SDK cannot faithfully represent as an api
        // error: §2 bans fabricating a 0/undefined status, so reject it via the validation type.
        const status = first.error?.status;
        if (status !== undefined) {
          throw new CountersApiError(msg, status, first.error);
        }
        throw new CountersValidationError(`${msg}; per-op problem carries no status`);
      }
    });
  }
}

/**
 * A scoped, read-only client for browser-safe `pk_` publishable tokens.
 *
 * Its public type exposes only operations accepted for publishable tokens. Use {@link CountersClient}
 * with a server-side organization key when writes or organization-wide reads are required.
 */
export class PublishableCountersClient {
  private readonly client: CountersClient;

  constructor(opts: PublishableCountersClientOptions) {
    this.client = new CountersClient(opts);
  }

  /** Get a read-only handle for a counter in this token's scope. */
  counter(key: string): PublishableCounterHandle {
    return new PublishableCounterHandleImpl(this.client.counter(key));
  }

  /** Release this client's resources. */
  close(): Promise<void> {
    return this.client.close();
  }
}

/** A read-only counter handle obtained from {@link PublishableCountersClient.counter}. */
export interface PublishableCounterHandle {
  /** The validated counter key. */
  readonly key: string;
  /** Current value. */
  value(): Promise<ValueResponse>;
  /** One member's time series (delta per bucket). Requires member series enabled on the counter. */
  series(params: SeriesParams & { member: string }): Promise<MemberSeriesResponse>;
  /** The dense per-member multi-series. Requires member series enabled on the counter. */
  series(params: SeriesParams & { groupBy: "member" }): Promise<MemberGroupSeriesResponse>;
  /** Time series (delta per bucket). */
  series(params: SeriesParams): Promise<SeriesResponse>;
  /** The ranked member leaderboard for this counter (top-N). */
  leaderboard(params?: LeaderboardParams): Promise<Leaderboard>;
  /** The windowed leaderboard: members ranked by activity over the trailing window. */
  leaderboard(params: WindowLeaderboardParams): Promise<WindowLeaderboard>;
  /** A read-only handle for one member of this counter's board. */
  member(member: string): PublishableMemberHandle;
}

/** A read-only member handle obtained from {@link PublishableCounterHandle.member}. */
export interface PublishableMemberHandle {
  /** The validated counter key. */
  readonly counterKey: string;
  /** The validated member key. */
  readonly member: string;
  /** This member's rank, percentile, and standing value. */
  get(params?: MemberGetParams): Promise<MemberSnapshot>;
}

class PublishableCounterHandleImpl implements PublishableCounterHandle {
  readonly key: string;

  constructor(private readonly handle: CounterHandle) {
    this.key = handle.key;
  }

  value(): Promise<ValueResponse> {
    return this.handle.value();
  }

  series(params: SeriesParams & { member: string }): Promise<MemberSeriesResponse>;
  series(params: SeriesParams & { groupBy: "member" }): Promise<MemberGroupSeriesResponse>;
  series(params: SeriesParams): Promise<SeriesResponse>;
  series(
    params: SeriesParams & { member?: string; groupBy?: "member" },
  ): Promise<SeriesResponse | MemberSeriesResponse | MemberGroupSeriesResponse> {
    return this.handle.series(params);
  }

  leaderboard(params?: LeaderboardParams): Promise<Leaderboard>;
  leaderboard(params: WindowLeaderboardParams): Promise<WindowLeaderboard>;
  leaderboard(
    params: LeaderboardParams & { window?: Window } = {},
  ): Promise<Leaderboard | WindowLeaderboard> {
    return this.handle.leaderboard(params);
  }

  member(member: string): PublishableMemberHandle {
    return new PublishableMemberHandleImpl(this.handle.member(member));
  }
}

class PublishableMemberHandleImpl implements PublishableMemberHandle {
  readonly counterKey: string;
  readonly member: string;

  constructor(private readonly handle: MemberHandle) {
    this.counterKey = handle.counterKey;
    this.member = handle.member;
  }

  get(params?: MemberGetParams): Promise<MemberSnapshot> {
    return this.handle.get(params);
  }
}

/** A typed handle to a single counter. Obtain one from {@link CountersClient.counter}. */
export class CounterHandle {
  /** @internal — use {@link CountersClient.counter}, which validates the key. */
  constructor(
    private readonly client: CountersClient,
    readonly key: string,
  ) {}

  /** Buffer an increment (flushed in the background; coalesced with other writes to this counter). */
  add(amount: AmountInput): void {
    this.client.enqueue(this.key, toAmount(amount));
  }

  /** Buffer a decrement. The counter may go negative. */
  subtract(amount: AmountInput): void {
    this.client.enqueue(this.key, -toAmount(amount));
  }

  /** Apply an increment immediately and return the new counter state. */
  addNow(amount: AmountInput, opts?: ApplyOptions): Promise<Counter> {
    return this.client.addNow(this.key, toAmount(amount), opts);
  }

  /** Apply a decrement immediately and return the new counter state. */
  subtractNow(amount: AmountInput, opts?: ApplyOptions): Promise<Counter> {
    return this.client.subtractNow(this.key, toAmount(amount), opts);
  }

  /** Reset the counter to zero (starts a new epoch; history retained). */
  clear(): Promise<Counter> {
    return this.client.clearCounter(this.key);
  }

  /** Delete (tombstone) the counter. */
  delete(): Promise<void> {
    return this.client.deleteCounter(this.key);
  }

  /** Current value. */
  value(): Promise<ValueResponse> {
    return this.client.getValue(this.key);
  }

  /** One member's time series (delta per bucket). Requires member series enabled on the counter. */
  series(params: SeriesParams & { member: string }): Promise<MemberSeriesResponse>;
  /** The dense per-member multi-series. Requires member series enabled on the counter. */
  series(params: SeriesParams & { groupBy: "member" }): Promise<MemberGroupSeriesResponse>;
  /** Time series (delta per bucket). */
  series(params: SeriesParams): Promise<SeriesResponse>;
  series(
    params: SeriesParams & { member?: string; groupBy?: "member" },
  ): Promise<SeriesResponse | MemberSeriesResponse | MemberGroupSeriesResponse> {
    return this.client.getSeries(this.key, params);
  }

  /** The ranked member leaderboard for this counter (top-N). */
  leaderboard(params?: LeaderboardParams): Promise<Leaderboard>;
  /** The windowed leaderboard: members ranked by summed activity over the trailing window. */
  leaderboard(params: WindowLeaderboardParams): Promise<WindowLeaderboard>;
  leaderboard(
    params: LeaderboardParams & { window?: Window } = {},
  ): Promise<Leaderboard | WindowLeaderboard> {
    return this.client.getLeaderboard(this.key, params);
  }

  /** A handle for one member of this counter's board. Validates the member key client-side. */
  member(member: string): MemberHandle {
    assertMemberKey(member);
    return new MemberHandle(this.client, this.key, member);
  }
}

/** A typed handle to a single member of a counter's board. Obtain one from {@link CounterHandle.member}. */
export class MemberHandle {
  /** @internal — use {@link CounterHandle.member}, which validates the member key. */
  constructor(
    private readonly client: CountersClient,
    readonly counterKey: string,
    readonly member: string,
  ) {}

  /** This member's rank, percentile, and standing value. */
  get(params?: MemberGetParams): Promise<MemberSnapshot> {
    return this.client.getMember(this.counterKey, this.member, params);
  }

  /** Remove this member from the current board (sum boards compensate the value into the group total). */
  remove(): Promise<MemberRemoved> {
    return this.client.removeMember(this.counterKey, this.member);
  }

  /** Add a non-negative delta to this member (sum board). Immediate — never buffered. */
  add(amount: AmountInput, opts?: MemberApplyOptions): Promise<MemberValue> {
    assertMemberMetadata(opts);
    return this.client.addToMember(this.counterKey, this.member, toAmount(amount), opts);
  }

  /** Subtract a non-negative delta from this member (sum board; the member may go negative). Immediate. */
  subtract(amount: AmountInput, opts?: MemberApplyOptions): Promise<MemberValue> {
    assertMemberMetadata(opts);
    return this.client.subtractFromMember(this.counterKey, this.member, toAmount(amount), opts);
  }

  /** Submit a signed score to a score board (latest/min/max). `mode` is required on the first submit. */
  submit(value: ValueInput, opts?: SubmitOptions): Promise<MemberValue> {
    assertMemberMetadata(opts);
    return this.client.submitMember(this.counterKey, this.member, toValue(value), opts);
  }
}

/** A typed handle to a single derived counter (a server-defined decimal expression over counters). Obtain one from {@link CountersClient.derived}. */
export class DerivedHandle {
  /** @internal — use {@link CountersClient.derived}, which validates the key. */
  constructor(
    private readonly client: CountersClient,
    readonly key: string,
  ) {}

  /** Evaluate the current value. `value` is `null` (with a `reason`) on division by zero. */
  value(): Promise<DerivedValueResponse> {
    return this.client.getDerivedValue(this.key);
  }

  /** Evaluate the derived expression per bucket over [from, to). Always dense; per-bucket holes are `v: null`. */
  series(params: DerivedSeriesParams): Promise<DerivedSeriesResponse> {
    return this.client.getDerivedSeries(this.key, params);
  }
}

function enc(key: string): string {
  return encodeURIComponent(key);
}

type WireCounter = Omit<Counter, "createdAt" | "updatedAt"> & {
  createdAt?: string | null;
  updatedAt?: string | null;
};

type WireCounterPage = Omit<CounterPage, "data"> & { data: WireCounter[] };

type WireLeaderboardEntry = Omit<LeaderboardEntry, "updatedAt"> & { updatedAt: string };

type WireLeaderboard = Omit<Leaderboard, "entries"> & { entries: WireLeaderboardEntry[] };

type WireMemberSnapshot = Omit<MemberSnapshot, "updatedAt"> & { updatedAt: string };

type WireSeriesPoint = { t: string; v: Value };

type WireSeriesResponse = Omit<SeriesResponse, "points"> & { points: WireSeriesPoint[] };

type WireMemberSeriesResponse = Omit<MemberSeriesResponse, "points"> & {
  points: WireSeriesPoint[];
};

type WireMemberSeriesEntry = Omit<MemberSeriesEntry, "points"> & { points: WireSeriesPoint[] };

type WireMemberGroupSeriesResponse = Omit<MemberGroupSeriesResponse, "series"> & {
  series: WireMemberSeriesEntry[];
};

function parseCounter(counter: WireCounter): Counter {
  const { createdAt, updatedAt, ...fields } = counter;
  return {
    ...fields,
    ...(createdAt == null ? {} : { createdAt: new Date(createdAt) }),
    ...(updatedAt == null ? {} : { updatedAt: new Date(updatedAt) }),
  };
}

function parseCounterPage(page: WireCounterPage): CounterPage {
  return { ...page, data: page.data.map(parseCounter) };
}

function parseLeaderboard(leaderboard: WireLeaderboard): Leaderboard {
  return {
    ...leaderboard,
    entries: leaderboard.entries.map((entry) => ({
      ...entry,
      updatedAt: new Date(entry.updatedAt),
    })),
  };
}

function parseMemberSnapshot(snapshot: WireMemberSnapshot): MemberSnapshot {
  return { ...snapshot, updatedAt: new Date(snapshot.updatedAt) };
}

function parseSeriesPoint(point: WireSeriesPoint): SeriesPoint {
  return { timestamp: new Date(point.t), value: point.v };
}

function parseSeriesResult(
  response: WireSeriesResponse | WireMemberSeriesResponse | WireMemberGroupSeriesResponse,
): SeriesResponse | MemberSeriesResponse | MemberGroupSeriesResponse {
  if ("series" in response) {
    return {
      ...response,
      series: response.series.map((entry) => ({
        ...entry,
        points: entry.points.map(parseSeriesPoint),
      })),
    };
  }
  return { ...response, points: response.points.map(parseSeriesPoint) };
}

function toIso(t: string | Date): string {
  return t instanceof Date ? t.toISOString() : t;
}

function applyBody(amount: bigint, opts?: ApplyOptions): Record<string, string> {
  const body: Record<string, string> = { amount: amount.toString() };
  if (opts?.occurredAt !== undefined) body.occurredAt = toIso(opts.occurredAt);
  return body;
}

/** Validate metadata (when present) before it is sent — a byte-cap breach fails fast, no request. */
function assertMemberMetadata(opts?: MemberApplyOptions): void {
  if (opts?.metadata !== undefined) assertMetadata(opts.metadata);
}

/** POST …/members/{member}/{add|subtract} body: `amount`, plus `metadata`/`occurredAt` when set. */
function memberAmountBody(amount: bigint, opts?: MemberApplyOptions): Record<string, string> {
  const body: Record<string, string> = { amount: amount.toString() };
  if (opts?.metadata !== undefined) body.metadata = opts.metadata;
  if (opts?.occurredAt !== undefined) body.occurredAt = toIso(opts.occurredAt);
  return body;
}

/** POST …/members/{member}/submit body: `value`, plus `mode`/`metadata`/`occurredAt` when set. */
function submitBody(value: bigint, opts?: SubmitOptions): Record<string, string> {
  const body: Record<string, string> = { value: value.toString() };
  if (opts?.mode !== undefined) body.mode = opts.mode;
  if (opts?.metadata !== undefined) body.metadata = opts.metadata;
  if (opts?.occurredAt !== undefined) body.occurredAt = toIso(opts.occurredAt);
  return body;
}

function normaliseWriteError(error: unknown): CountersError {
  return error instanceof CountersError
    ? error
    : new CountersTransportError(`unexpected batch submission failure: ${String(error)}`, error);
}
