import {
  CountersApiError,
  CountersClient,
  CountersError,
  CountersTransportError,
  CountersValidationError,
  PublishableCountersClient,
  type CountersClientOptions,
  type PublishableCounterHandle,
  type PublishableMemberHandle,
  type SeriesPoint,
} from "../src/index.js";

declare const point: SeriesPoint;
point.timestamp.toISOString();
point.value.toString();
// @ts-expect-error `t` is a compact wire key, not part of the public point.
point.t;
// @ts-expect-error `v` is a compact wire key, not part of the public point.
point.v;

const writable = new CountersClient({
  apiKey: "ck_server",
  batch: {
    onError(error) {
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
void writableCounter.clear();
void writableCounter.delete();
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
