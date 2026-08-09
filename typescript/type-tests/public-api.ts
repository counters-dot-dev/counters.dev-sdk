import {
  CountersApiError,
  CountersClient,
  CountersError,
  CountersTransportError,
  CountersValidationError,
  PublishableCountersClient,
  type CountersClientOptions,
  type DerivedSeriesPoint,
  type MemberGroupSeriesResponse,
  type MemberSeriesMode,
  type MemberSeriesResponse,
  type Mode,
  type PublishableCounterHandle,
  type PublishableMemberHandle,
  type SeriesResponse,
  type SeriesPoint,
  type Usage,
  type WindowLeaderboard,
} from "../src/index.js";

declare const point: SeriesPoint;
point.timestamp.toISOString();
point.value.toString();
// @ts-expect-error `t` is a compact wire key, not part of the public point.
point.t;
// @ts-expect-error `v` is a compact wire key, not part of the public point.
point.v;

declare const derivedPoint: DerivedSeriesPoint;
derivedPoint.timestamp.toISOString();
if (derivedPoint.value !== null) derivedPoint.value.toString();
// @ts-expect-error `t` is a compact wire key, not part of the public derived point.
derivedPoint.t;
// @ts-expect-error `v` is a compact wire key, not part of the public derived point.
derivedPoint.v;

declare const seriesResponse: SeriesResponse;
seriesResponse.range.from.toISOString();
seriesResponse.range.to.toISOString();
seriesResponse.timeZone?.toString();
// @ts-expect-error `tz` is a compact wire key, not part of the public response.
seriesResponse.tz;

// Internal wire helpers must NOT be part of the published surface (no public method takes them).
// @ts-expect-error `Operation` is an internal batch wire shape, not a published type.
void ({} as import("../src/index.js").Operation);
// @ts-expect-error `BatchResult` is an internal batch wire shape, not a published type.
void ({} as import("../src/index.js").BatchResult);

declare const memberSeries: MemberSeriesResponse;
const memberSeriesMode: MemberSeriesMode = memberSeries.mode;
void memberSeriesMode;

declare const groupSeries: MemberGroupSeriesResponse;
// The spec requires a top-level `mode` on the grouped series (delta on sum boards, else the board mode).
const groupSeriesMode: MemberSeriesMode = groupSeries.mode;
void groupSeriesMode;
groupSeries.memberCount.toFixed();
groupSeries.selectedCount.toFixed();
groupSeries.truncated.valueOf();

declare const usage: Usage;
usage.operations.resetsAt.toISOString();
usage.limits.rateLimitRequestsPerSecond.toFixed();
usage.limits.monthlyOperationsQuota?.toFixed();
// @ts-expect-error `ops` is a compact wire key, not part of the public usage response.
usage.ops;
// @ts-expect-error wire abbreviation is not part of the public usage limits.
usage.limits.rateLimitRps;
// @ts-expect-error wire abbreviation is not part of the public usage limits.
usage.limits.monthlyOpsQuota;

declare const windowLeaderboard: WindowLeaderboard;
windowLeaderboard.effectiveStart.toISOString();
windowLeaderboard.effectiveEnd.toISOString();
// A windowed board follows the board mode; `total` is present only on sum boards.
const windowMode: Mode = windowLeaderboard.mode;
void windowMode;
windowLeaderboard.total?.toString();
// @ts-expect-error `total` is absent on score-board windows — narrow before use.
windowLeaderboard.total.toString();

declare const usageQuota: Usage;
// Absent wire quotas are normalised to null — never undefined.
const opsQuota: number | null = usageQuota.operations.quota;
const monthlyQuota: number | null = usageQuota.limits.monthlyOperationsQuota;
void opsQuota;
void monthlyQuota;

const writable = new CountersClient({
  apiKey: "ck_server",
  batch: {
    onError(failure) {
      failure.counterKey.toUpperCase();
      BigInt(failure.delta);
      failure.member?.toUpperCase();
      failure.idempotencyKey.toUpperCase();
      const error = failure.error;
      const sdkError: CountersError = error;
      if (error instanceof CountersApiError) error.status.toFixed();
      if (error instanceof CountersTransportError) void error.cause;
      if (error instanceof CountersValidationError) error.message.toString();
      void sdkError;
    },
  },
});
const writableCounter = writable.counter("views");
writableCounter.add(1);
writableCounter.subtract(1);
void writableCounter.addNow(1);
void writableCounter.subtractNow(1);
void writableCounter.addNow(1, { occurredAt: new Date() });
void writableCounter.addNow(1, { idempotencyKey: "write-1" });
// @ts-expect-error event timestamps use native Date values.
void writableCounter.addNow(1, { occurredAt: "2026-01-01T00:00:00Z" });
void writableCounter.clear({ idempotencyKey: "clear-1" });
void writableCounter.delete({ idempotencyKey: "delete-1" });
void writable.list();
void writable.usage();
void writable.derived("conversion").value();
void writable.flush();
void writable.close();

const publishable = new PublishableCountersClient({ apiKey: "pk_browser" });
const readOnlyCounter = publishable.counter("views");
const typedReadOnlyCounter: PublishableCounterHandle = readOnlyCounter;
readOnlyCounter.key.toString();
void readOnlyCounter.value();
void readOnlyCounter.series({ from: new Date(), to: new Date(), bucket: "1h" });
void readOnlyCounter.series({
  from: new Date(),
  to: new Date(),
  bucket: "1d",
  timeZone: "Europe/London",
});
// @ts-expect-error series bounds use native Date values.
void readOnlyCounter.series({ from: "2026-01-01T00:00:00Z", to: new Date(), bucket: "1h" });
// @ts-expect-error `tz` is the wire key; public params use `timeZone`.
void readOnlyCounter.series({ from: new Date(), to: new Date(), bucket: "1h", tz: "UTC" });
void readOnlyCounter.series({ from: new Date(), to: new Date(), bucket: "1h", member: "alice" });
void readOnlyCounter.series({ from: new Date(), to: new Date(), bucket: "1h", groupBy: "member" });
void readOnlyCounter.leaderboard();
void readOnlyCounter.leaderboard({ window: "7d" });
void readOnlyCounter.member("alice").get();
void publishable.close();

// @ts-expect-error publishable clients do not expose organization-wide listing.
publishable.list();
// @ts-expect-error publishable clients do not expose organization usage.
publishable.usage();
// @ts-expect-error publishable clients cannot read full-key-only derived counters.
publishable.derived("conversion");
// @ts-expect-error publishable clients have no write buffer to flush.
publishable.flush();
// @ts-expect-error publishable clients do not accept writable batch configuration.
new PublishableCountersClient({ apiKey: "pk_browser", batch: { enabled: false } });

const writableOptionsWithBatch: CountersClientOptions = {
  apiKey: "ck_server",
  batch: { enabled: false },
};
// @ts-expect-error writable options cannot be passed through with a hidden batch configuration.
new PublishableCountersClient(writableOptionsWithBatch);

// @ts-expect-error publishable counter handles cannot buffer increments.
readOnlyCounter.add(1);
// @ts-expect-error publishable counter handles cannot buffer decrements.
readOnlyCounter.subtract(1);
// @ts-expect-error publishable counter handles cannot perform immediate increments.
readOnlyCounter.addNow(1);
// @ts-expect-error publishable counter handles cannot perform immediate decrements.
readOnlyCounter.subtractNow(1);
// @ts-expect-error publishable counter handles cannot clear counters.
readOnlyCounter.clear();
// @ts-expect-error publishable counter handles cannot delete counters.
readOnlyCounter.delete();

const readOnlyMember = readOnlyCounter.member("alice");
const typedReadOnlyMember: PublishableMemberHandle = readOnlyMember;
readOnlyMember.counterKey.toString();
readOnlyMember.member.toString();
// @ts-expect-error publishable member handles cannot add.
readOnlyMember.add(1);
// @ts-expect-error publishable member handles cannot subtract.
readOnlyMember.subtract(1);
// @ts-expect-error publishable member handles cannot submit scores.
readOnlyMember.submit(1);
// @ts-expect-error publishable member handles cannot remove members.
readOnlyMember.remove();

void typedReadOnlyCounter;
void typedReadOnlyMember;
