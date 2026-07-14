# Raid completion example

This narrative app sketches the reporting path after a party clears Molten Core. It buffers a
fire-and-forget increment to the global `raids-completed` counter, then records each player's damage
as a member of the cumulative `raid-molten-core` sum leaderboard. The post-raid screen reads both the
top five and each party member's own rank and percentile.

The leaderboard is the important modeling choice: one counter per player followed by client-side
sorting would duplicate ranking logic and require many reads. Member values let counters.dev own the
ranking, ties, percentiles, and aggregate total. Individual raid contributions fit in a Java `long`,
but cumulative season damage need not, so response strings are converted directly to `BigInteger`
and never pass through a floating-point or 64-bit parser.
Leaderboard and member-snapshot update times arrive as native `java.time.Instant` values; the app
prints `LeaderboardEntry.updatedAt()` directly without parsing the wire timestamp.

The error branches also reflect game-server priorities. API quota failures are logged without
rolling back the raid, and client-side validation failures are treated as code/configuration bugs.
For a member write that still ends in a transport failure after the SDK's built-in attempts, the app
retries promptly with the exact same member, delta, and caller-generated idempotency key. The service
de-duplicates that replay within its deduplication window; the example does not imply safety after
that unspecified window. Background telemetry failures arrive as `WriteFailure` values through
`onBatchError`, including the failed counter, signed coalesced delta, actual idempotency key, and one
of the three typed `CountersException` subtypes.

The app is compile-checked rather than run in CI. With a real API key, run it from this directory:

```bash
COUNTERS_API_KEY=ck_... gradle run
```
