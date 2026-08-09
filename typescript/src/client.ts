import { Batcher, toWriteFailure } from "./batcher.js";
import type { OperationFailure } from "./batcher.js";
import {
  CountersApiError,
  CountersError,
  CountersTransportError,
  CountersValidationError,
} from "./errors.js";
import { Http } from "./http.js";
import { resolveIdempotencyKey } from "./idempotency.js";
import {
  assertBucket,
  assertCounterKey,
  assertDeclareCountersRequest,
  assertMemberKey,
  assertMetadata,
  assertSetMemberSeriesOptions,
  assertWindow,
  describeValue,
  toAmount,
  toValue,
} from "./validation.js";
import type {
  AmountInput,
  ApplyOptions,
  BatchResponse,
  Counter,
  CounterDeclarationResult,
  CounterPage,
  DeclareCountersRequest,
  DeclareCountersResponse,
  DerivedSeriesPoint,
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
  MemberSeriesConfig,
  MemberSeriesEntry,
  MemberSeriesResponse,
  MemberSnapshot,
  MemberValue,
  Operation,
  SeriesParams,
  SeriesPoint,
  SeriesResponse,
  SetMemberSeriesOptions,
  SubmitOptions,
  Usage,
  Value,
  ValueInput,
  ValueResponse,
  Window,
  WindowLeaderboard,
  WindowLeaderboardParams,
  WriteFailure,
  WriteOptions,
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
    onError?: (failure: WriteFailure) => void;
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
  private readonly onWriteError?: (failure: WriteFailure) => void;

  constructor(opts: CountersClientOptions) {
    if (opts == null || typeof opts !== "object" || typeof opts.apiKey !== "string" || !opts.apiKey) {
      throw new CountersValidationError("CountersClient: apiKey is required");
    }
    if (opts.fetch !== undefined && typeof opts.fetch !== "function") {
      throw new CountersValidationError("fetch must be a function");
    }
    const batch = opts.batch;
    if (batch !== undefined) {
      if (batch === null || typeof batch !== "object" || Array.isArray(batch)) {
        throw new CountersValidationError("batch options must be an object");
      }
      if (batch.enabled !== undefined && typeof batch.enabled !== "boolean") {
        throw new CountersValidationError("batch.enabled must be a boolean");
      }
      if (
        batch.maxBatchSize !== undefined
        && (!Number.isInteger(batch.maxBatchSize) || batch.maxBatchSize < 1)
      ) {
        throw new CountersValidationError("batch.maxBatchSize must be a positive integer");
      }
      if (
        batch.intervalMs !== undefined
        && (typeof batch.intervalMs !== "number" || !Number.isFinite(batch.intervalMs))
      ) {
        throw new CountersValidationError("batch.intervalMs must be a finite number");
      }
      if (batch.onError !== undefined && typeof batch.onError !== "function") {
        throw new CountersValidationError("batch.onError must be a function");
      }
    }
    this.http = new Http({
      baseUrl: opts.baseUrl ?? DEFAULT_BASE_URL,
      apiKey: opts.apiKey,
      fetch: opts.fetch,
      maxRetries: opts.maxRetries,
      backoffMs: opts.backoffMs,
      timeoutMs: opts.timeoutMs,
    });
    this.batchEnabled = batch?.enabled ?? true;
    this.onWriteError = batch?.onError;
    this.batcher = new Batcher((ops) => this.submitBatch(ops), {
      maxBatchSize: batch?.maxBatchSize ?? 100,
      intervalMs: batch?.intervalMs ?? 1000,
      onError: batch?.onError,
    });
  }

  /** Get a handle for a counter. Throws CountersValidationError if the key is invalid. */
  counter(key: string): CounterHandle {
    assertCounterKey(key);
    return new CounterHandle(this, key);
  }

  /** List counters in the organization. */
  list(opts: { cursor?: string; limit?: number } = {}): Promise<CounterPage> {
    if (opts == null || typeof opts !== "object") {
      throw new CountersValidationError("list options must be an object");
    }
    return parseWireResponse(
      this.http.request<WireCounterPage>("GET", "/counters", {
        query: { cursor: opts.cursor, limit: opts.limit },
      }),
      parseCounterPage,
    );
  }

  /** Atomically create or verify the complete known counter set and set the implicit-create policy. */
  declare(request: DeclareCountersRequest): Promise<DeclareCountersResponse> {
    assertDeclareCountersRequest(request);
    return parseWireResponse(
      this.http.request<WireDeclareCountersResponse>("POST", "/counters", { body: request }),
      parseDeclareCountersResponse,
    );
  }

  /** Current quota state for the organization (month-to-date operations, counter headroom, plan limits). */
  usage(): Promise<Usage> {
    return parseWireResponse(this.http.request<WireUsage>("GET", "/usage"), parseUsage);
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
    return parseWireResponse(this.http
      .request<WireCounter>("POST", `/counters/${enc(key)}/add`, {
        body: applyBody(amount, opts),
        idempotencyKey: resolveIdempotencyKey(opts?.idempotencyKey),
      }), parseCounter);
  }

  /** @internal */
  subtractNow(key: string, amount: bigint, opts?: ApplyOptions): Promise<Counter> {
    return parseWireResponse(this.http
      .request<WireCounter>("POST", `/counters/${enc(key)}/subtract`, {
        body: applyBody(amount, opts),
        idempotencyKey: resolveIdempotencyKey(opts?.idempotencyKey),
      }), parseCounter);
  }

  /** @internal */
  clearCounter(key: string, opts?: WriteOptions): Promise<Counter> {
    return parseWireResponse(this.http
      .request<WireCounter>("POST", `/counters/${enc(key)}/clear`, {
        idempotencyKey: resolveIdempotencyKey(opts?.idempotencyKey),
      }), parseCounter);
  }

  /** @internal */
  deleteCounter(key: string, opts?: WriteOptions): Promise<void> {
    return this.http.request<void>("DELETE", `/counters/${enc(key)}`, {
      idempotencyKey: resolveIdempotencyKey(opts?.idempotencyKey),
    });
  }

  /** @internal */
  getValue(key: string): Promise<ValueResponse> {
    return this.http.request<ValueResponse>("GET", `/counters/${enc(key)}/value`);
  }

  /** @internal */
  getCounter(key: string): Promise<Counter> {
    return parseWireResponse(
      this.http.request<WireCounter>("GET", `/counters/${enc(key)}`),
      parseCounter,
    );
  }

  /** @internal */
  setMemberSeries(
    key: string,
    enabled: boolean,
    options?: SetMemberSeriesOptions,
  ): Promise<MemberSeriesConfig> {
    if (typeof enabled !== "boolean") {
      throw new CountersValidationError("member-series enabled must be a boolean");
    }
    assertSetMemberSeriesOptions(options);
    return parseWireResponse(
      this.http.request<WireMemberSeriesConfig>("PUT", `/counters/${enc(key)}/member-series`, {
        body: {
          enabled,
          ...(options?.expectedEpoch === undefined ? {} : { expectedEpoch: options.expectedEpoch }),
        },
      }),
      parseMemberSeriesConfig,
    );
  }

  /** @internal */
  getSeries(
    key: string,
    params: SeriesParams & { member?: string; groupBy?: "member" },
  ): Promise<SeriesResponse | MemberSeriesResponse | MemberGroupSeriesResponse> {
    if (params == null || typeof params !== "object") {
      throw new CountersValidationError("series params are required");
    }
    assertBucket(params.bucket);
    // `member` and `groupBy` are mutually exclusive (the server answers 400) — reject the
    // combination client-side before any network I/O (conformance/series member-and-groupby case).
    if (params.member !== undefined && params.groupBy !== undefined) {
      throw new CountersValidationError(
        "series: `member` and `groupBy` are mutually exclusive — set at most one",
      );
    }
    if (params.member !== undefined) assertMemberKey(params.member);
    return parseWireResponse(
      this.http.request<WireSeriesResponse | WireMemberSeriesResponse | WireMemberGroupSeriesResponse>(
        "GET",
        `/counters/${enc(key)}/series`,
        {
          query: {
            from: toIso(params.from),
            to: toIso(params.to),
            bucket: params.bucket,
            mode: params.mode,
            tz: params.timeZone,
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
      ),
      parseSeriesResult,
    );
  }

  /** @internal */
  getLeaderboard(
    key: string,
    params: LeaderboardParams & { window?: Window },
  ): Promise<Leaderboard | WindowLeaderboard> {
    if (params == null || typeof params !== "object") {
      throw new CountersValidationError("leaderboard params must be an object");
    }
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
      return parseWireResponse(
        this.http.request<WireWindowLeaderboard>("GET", path, opts),
        parseWindowLeaderboard,
      );
    }
    return parseWireResponse(this.http.request<WireLeaderboard>("GET", path, opts), parseLeaderboard);
  }

  /** @internal */
  getMember(key: string, member: string, params?: MemberGetParams): Promise<MemberSnapshot> {
    return parseWireResponse(
      this.http.request<WireMemberSnapshot>("GET", `/counters/${enc(key)}/members/${enc(member)}`, {
        query: { epoch: params?.epoch, order: params?.order },
      }),
      parseMemberSnapshot,
    );
  }

  /** @internal */
  removeMember(key: string, member: string, opts?: WriteOptions): Promise<MemberRemoved> {
    return this.http.request<MemberRemoved>(
      "DELETE",
      `/counters/${enc(key)}/members/${enc(member)}`,
      { idempotencyKey: resolveIdempotencyKey(opts?.idempotencyKey) },
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
      { body: submitBody(value, opts), idempotencyKey: resolveIdempotencyKey(opts?.idempotencyKey) },
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
      { body: memberAmountBody(amount, opts), idempotencyKey: resolveIdempotencyKey(opts?.idempotencyKey) },
    );
  }

  /** @internal */
  getDerivedValue(key: string): Promise<DerivedValueResponse> {
    return this.http.request<DerivedValueResponse>("GET", `/derived/${enc(key)}/value`);
  }

  /** @internal */
  getDerivedSeries(key: string, params: DerivedSeriesParams): Promise<DerivedSeriesResponse> {
    if (params == null || typeof params !== "object") {
      throw new CountersValidationError("derived series params are required");
    }
    assertBucket(params.bucket);
    return parseWireResponse(
      this.http.request<WireDerivedSeriesResponse>("GET", `/derived/${enc(key)}/series`, {
        query: {
          from: toIso(params.from),
          to: toIso(params.to),
          bucket: params.bucket,
          tz: params.timeZone,
        },
      }),
      parseDerivedSeries,
    );
  }

  private fireSingle(key: string, delta: bigint): void {
    // Match the buffered path: a write after close() has no worker to observe it — surface the misuse.
    if (this.batcher.isClosed()) {
      throw new CountersValidationError("cannot enqueue on a closed client");
    }
    const operation: Operation =
      delta >= 0n
        ? { counterKey: key, operation: "add", amount: delta.toString(), idempotencyKey: resolveIdempotencyKey(undefined) }
        : { counterKey: key, operation: "subtract", amount: (-delta).toString(), idempotencyKey: resolveIdempotencyKey(undefined) };
    // Fire-and-forget, like a background flush — so failures route to the same onError sink
    // (previously they were swallowed, which silently dropped counted writes).
    void this.submitBatch([operation])
      .then((failures) => {
        for (const failure of failures) this.onWriteError?.(toWriteFailure(failure));
      })
      .catch((error) =>
        this.onWriteError?.(
          toWriteFailure({ operation, error: normaliseWriteError(error) }),
        ),
      );
  }

  private submitBatch(ops: Operation[]): Promise<readonly OperationFailure[]> {
    return this.http
      .request<BatchResponse>("POST", "/batch", { body: { operations: ops.map(toWireOperation) } })
      .then((res) => {
        // The HTTP 200 only means the batch was accepted; each operation carries its own status. A
        // per-op "error" (e.g. a counter/quota cap) would otherwise vanish silently — surface it so the
        // buffered path routes it to onError and the immediate path can observe it.
        try {
          if (res === null || typeof res !== "object" || !Array.isArray(res.results)) {
            throw new CountersValidationError("batch response: results must be an array");
          }
          if (res.results.length !== ops.length) {
            throw new CountersValidationError(
              `batch response: expected ${ops.length} result(s), got ${res.results.length}`,
            );
          }
          const opsByCounter = new Map(ops.map((operation) => [operation.counterKey, operation]));
          if (opsByCounter.size !== ops.length) {
            throw new CountersValidationError("batch request contains duplicate counter keys");
          }
          const seen = new Set<string>();
          const failures: OperationFailure[] = [];
          for (const [index, rawResult] of res.results.entries()) {
            if (rawResult === null || typeof rawResult !== "object" || Array.isArray(rawResult)) {
              throw new CountersValidationError(`batch response: result ${index} must be an object`);
            }
            const result = rawResult as unknown as Record<string, unknown>;
            const counterKey = result.counterKey;
            if (typeof counterKey !== "string") {
              throw new CountersValidationError(
                `batch response: result ${index} counterKey must be a string`,
              );
            }
            const operation = opsByCounter.get(counterKey);
            if (operation === undefined) {
              throw new CountersValidationError(
                `batch response contains a result for unknown counter ${describeValue(counterKey)}`,
              );
            }
            if (seen.has(counterKey)) {
              throw new CountersValidationError(
                `batch response contains duplicate results for counter ${describeValue(counterKey)}`,
              );
            }
            seen.add(counterKey);
            const resultStatus = result.status;
            if (
              resultStatus !== "applied"
              && resultStatus !== "deduplicated"
              && resultStatus !== "error"
            ) {
              throw new CountersValidationError(
                `batch response: invalid status ${describeValue(resultStatus)} for counter ${describeValue(counterKey)}`,
              );
            }
            if (resultStatus !== "error") continue;

            const problem = result.error;
            const problemRecord = problem !== null && typeof problem === "object" && !Array.isArray(problem)
              ? problem as Record<string, unknown>
              : undefined;
            const title = typeof problemRecord?.title === "string" ? problemRecord.title : "error";
            const msg = `batch operation failed (${counterKey}: ${title})`;
            const status = problemRecord?.status;
            const error = status === undefined
              ? new CountersValidationError(`${msg}; per-op problem carries no status`)
              : typeof status === "number" && Number.isInteger(status) && status >= 100 && status <= 599
                ? new CountersApiError(msg, status, problem as import("./types.js").Problem)
                : new CountersValidationError(`${msg}; per-op problem carries an invalid status`);
            failures.push({ operation, error });
          }
          if (seen.size !== ops.length) {
            throw new CountersValidationError("batch response does not cover every submitted operation");
          }
          return failures;
        } catch (error) {
          if (error instanceof CountersError) throw error;
          throw new CountersValidationError(
            `invalid batch response shape: ${describeValue(error)}`,
            error,
          );
        }
      })
      .catch((error) => {
        throw normaliseResponseError(error);
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
  /** One member's series (delta per bucket on a sum board; sparse best/latest scores on a score board). Requires member series enabled. */
  series(params: SeriesParams & { member: string }): Promise<MemberSeriesResponse>;
  /** The per-member multi-series (dense on a sum board, sparse per member on a score board). Requires member series enabled. */
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
  clear(opts?: WriteOptions): Promise<Counter> {
    return this.client.clearCounter(this.key, opts);
  }

  /** Delete (tombstone) the counter. */
  delete(opts?: WriteOptions): Promise<void> {
    return this.client.deleteCounter(this.key, opts);
  }

  /** Current value. */
  value(): Promise<ValueResponse> {
    return this.client.getValue(this.key);
  }

  /** Full counter detail, including member mode and dimensional-series configuration. */
  get(): Promise<Counter> {
    return this.client.getCounter(this.key);
  }

  /** Enable or disable per-member time series, optionally guarded by the current epoch. */
  setMemberSeries(
    enabled: boolean,
    options?: SetMemberSeriesOptions,
  ): Promise<MemberSeriesConfig> {
    return this.client.setMemberSeries(this.key, enabled, options);
  }

  /** One member's series (delta per bucket on a sum board; sparse best/latest scores on a score board). Requires member series enabled. */
  series(params: SeriesParams & { member: string }): Promise<MemberSeriesResponse>;
  /** The per-member multi-series (dense on a sum board, sparse per member on a score board). Requires member series enabled. */
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
  /** The windowed leaderboard: members ranked by their activity over the trailing window. */
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
  remove(opts?: WriteOptions): Promise<MemberRemoved> {
    return this.client.removeMember(this.counterKey, this.member, opts);
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

  /** Evaluate the derived expression per bucket over [from, to). Always dense; per-bucket holes have `value: null`. */
  series(params: DerivedSeriesParams): Promise<DerivedSeriesResponse> {
    return this.client.getDerivedSeries(this.key, params);
  }
}

function enc(key: string): string {
  return encodeURIComponent(key);
}

type WireCounter = Omit<Counter, "createdAt" | "updatedAt" | "memberSeriesEnabledAt"> & {
  createdAt?: string | null;
  updatedAt?: string | null;
  memberSeriesEnabledAt?: string | null;
};

type WireCounterDeclarationResult = Omit<CounterDeclarationResult, "memberSeriesEnabledAt"> & {
  memberSeriesEnabledAt?: string | null;
};

type WireDeclareCountersResponse = Omit<DeclareCountersResponse, "results"> & {
  results: WireCounterDeclarationResult[];
};

type WireMemberSeriesConfig = Omit<MemberSeriesConfig, "enabledAt"> & {
  enabledAt?: string | null;
};

type WireCounterPage = Omit<CounterPage, "data"> & { data: WireCounter[] };

type WireLeaderboardEntry = Omit<LeaderboardEntry, "updatedAt"> & { updatedAt: string };

type WireLeaderboard = Omit<Leaderboard, "entries"> & { entries: WireLeaderboardEntry[] };

type WireMemberSnapshot = Omit<MemberSnapshot, "updatedAt"> & { updatedAt: string };

type WireSeriesPoint = { t: string; v: Value };

type WireDateRange = { from: string; to: string };

type WireSeriesResponse = Omit<SeriesResponse, "timeZone" | "range" | "points"> & {
  tz?: string;
  range: WireDateRange;
  points: WireSeriesPoint[];
};

type WireMemberSeriesResponse = Omit<MemberSeriesResponse, "timeZone" | "range" | "points"> & {
  tz?: string;
  range: WireDateRange;
  points: WireSeriesPoint[];
};

type WireMemberSeriesEntry = Omit<MemberSeriesEntry, "points"> & { points: WireSeriesPoint[] };

type WireMemberGroupSeriesResponse = Omit<MemberGroupSeriesResponse, "timeZone" | "range" | "series"> & {
  tz?: string;
  range: WireDateRange;
  series: WireMemberSeriesEntry[];
};

type WireDerivedSeriesPoint = { t: string; v: DerivedSeriesPoint["value"] };

type WireDerivedSeriesResponse = Omit<
  DerivedSeriesResponse,
  "timeZone" | "range" | "points"
> & {
  tz?: string;
  range: WireDateRange;
  points: WireDerivedSeriesPoint[];
};

type WireUsage = Omit<Usage, "operations" | "limits"> & {
  // `quota`/`monthlyOpsQuota` are optional on the wire (unlimited plans omit them); the public type
  // normalises absent to null so callers branch on one representation.
  ops: Omit<Usage["operations"], "resetsAt" | "quota"> & { resetsAt: string; quota?: number | null };
  limits: {
    rateLimitRps: number;
    maxCounters: number;
    monthlyOpsQuota?: number | null;
  };
};

type WireWindowLeaderboard = Omit<WindowLeaderboard, "effectiveStart" | "effectiveEnd"> & {
  effectiveStart: string;
  effectiveEnd: string;
};

type WireOperation = Omit<Operation, "operation" | "occurredAt"> & {
  op: Operation["operation"];
  occurredAt?: string;
};

function parseCounter(counter: WireCounter): Counter {
  const { createdAt, updatedAt, memberSeriesEnabledAt, ...fields } = counter;
  return {
    ...fields,
    ...(createdAt == null ? {} : { createdAt: parseDate(createdAt, "counter.createdAt") }),
    ...(updatedAt == null ? {} : { updatedAt: parseDate(updatedAt, "counter.updatedAt") }),
    ...(memberSeriesEnabledAt == null
      ? {}
      : {
          memberSeriesEnabledAt: parseDate(
            memberSeriesEnabledAt,
            "counter.memberSeriesEnabledAt",
          ),
        }),
  };
}

function parseDeclareCountersResponse(response: WireDeclareCountersResponse): DeclareCountersResponse {
  return {
    ...response,
    results: response.results.map(({ memberSeriesEnabledAt, ...result }) => ({
      ...result,
      ...(memberSeriesEnabledAt == null
        ? {}
        : {
            memberSeriesEnabledAt: parseDate(
              memberSeriesEnabledAt,
              "declaration result.memberSeriesEnabledAt",
            ),
          }),
    })),
  };
}

function parseMemberSeriesConfig(config: WireMemberSeriesConfig): MemberSeriesConfig {
  const { enabledAt, ...fields } = config;
  return {
    ...fields,
    ...(enabledAt == null
      ? {}
      : { enabledAt: parseDate(enabledAt, "member-series configuration.enabledAt") }),
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
      updatedAt: parseDate(entry.updatedAt, "leaderboard entry.updatedAt"),
    })),
  };
}

function parseMemberSnapshot(snapshot: WireMemberSnapshot): MemberSnapshot {
  return { ...snapshot, updatedAt: parseDate(snapshot.updatedAt, "member.updatedAt") };
}

function parseSeriesPoint(point: WireSeriesPoint): SeriesPoint {
  return { timestamp: parseDate(point.t, "series point timestamp"), value: point.v };
}

function parseDateRange(range: WireDateRange): { from: Date; to: Date } {
  return {
    from: parseDate(range.from, "series range.from"),
    to: parseDate(range.to, "series range.to"),
  };
}

function publicTimeZone(tz: string | undefined): { timeZone?: string } {
  return tz === undefined ? {} : { timeZone: tz };
}

function parseSeriesResult(
  response: WireSeriesResponse | WireMemberSeriesResponse | WireMemberGroupSeriesResponse,
): SeriesResponse | MemberSeriesResponse | MemberGroupSeriesResponse {
  if ("series" in response) {
    const { tz, range, series, ...fields } = response;
    return {
      ...fields,
      ...publicTimeZone(tz),
      range: parseDateRange(range),
      series: series.map((entry) => ({
        ...entry,
        points: entry.points.map(parseSeriesPoint),
      })),
    };
  }
  const { tz, range, points, ...fields } = response;
  return {
    ...fields,
    ...publicTimeZone(tz),
    range: parseDateRange(range),
    points: points.map(parseSeriesPoint),
  };
}

function parseDerivedSeries(response: WireDerivedSeriesResponse): DerivedSeriesResponse {
  const { tz, range, points, ...fields } = response;
  return {
    ...fields,
    ...publicTimeZone(tz),
    range: parseDateRange(range),
    points: points.map((point) => ({
      timestamp: parseDate(point.t, "derived series point timestamp"),
      value: point.v,
    })),
  };
}

function parseUsage(usage: WireUsage): Usage {
  const { ops, limits, ...fields } = usage;
  return {
    ...fields,
    operations: {
      ...ops,
      quota: ops.quota ?? null,
      resetsAt: parseDate(ops.resetsAt, "usage.operations.resetsAt"),
    },
    limits: {
      rateLimitRequestsPerSecond: limits.rateLimitRps,
      maxCounters: limits.maxCounters,
      monthlyOperationsQuota: limits.monthlyOpsQuota ?? null,
    },
  };
}

function parseWindowLeaderboard(leaderboard: WireWindowLeaderboard): WindowLeaderboard {
  return {
    ...leaderboard,
    effectiveStart: parseDate(leaderboard.effectiveStart, "leaderboard.effectiveStart"),
    effectiveEnd: parseDate(leaderboard.effectiveEnd, "leaderboard.effectiveEnd"),
  };
}

function toWireOperation(operation: Operation): WireOperation {
  const { operation: op, occurredAt, ...fields } = operation;
  return {
    ...fields,
    op,
    ...(occurredAt === undefined ? {} : { occurredAt: toIso(occurredAt) }),
  };
}

function toIso(t: Date): string {
  if (!(t instanceof Date)) {
    throw new CountersValidationError(`expected a Date, got ${describeValue(t)}`);
  }
  try {
    return t.toISOString();
  } catch (error) {
    throw new CountersValidationError("date must be valid", error);
  }
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
    : new CountersTransportError(
        `unexpected batch submission failure: ${describeValue(error)}`,
        error,
      );
}

function parseDate(value: unknown, field: string): Date {
  if (typeof value !== "string") {
    throw new CountersValidationError(`${field} must be an RFC 3339 string`);
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    throw new CountersValidationError(`${field} is not a valid date-time: ${JSON.stringify(value)}`);
  }
  return parsed;
}

function normaliseResponseError(error: unknown): CountersError {
  return error instanceof CountersError
    ? error
    : new CountersValidationError(`invalid response shape: ${describeValue(error)}`, error);
}

function parseWireResponse<T, R>(response: Promise<T>, parser: (value: T) => R): Promise<R> {
  return response.then((value) => {
    try {
      return parser(value);
    } catch (error) {
      throw normaliseResponseError(error);
    }
  });
}
