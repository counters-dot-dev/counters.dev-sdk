package dev.counters.examples;

import dev.counters.sdk.CounterHandle;
import dev.counters.sdk.CountersApiException;
import dev.counters.sdk.CountersClient;
import dev.counters.sdk.CountersException;
import dev.counters.sdk.CountersTransportException;
import dev.counters.sdk.CountersValidationException;
import dev.counters.sdk.Idempotency;
import dev.counters.sdk.Leaderboard;
import dev.counters.sdk.LeaderboardEntry;
import dev.counters.sdk.LeaderboardParams;
import dev.counters.sdk.MemberGetParams;
import dev.counters.sdk.MemberSnapshot;
import dev.counters.sdk.MemberWriteOptions;
import dev.counters.sdk.WriteFailure;

import java.math.BigInteger;
import java.util.List;

public final class RaidCompletion {
    private static final int TOP_N = 5;

    private record Contribution(String playerId, long damage) {}

    public static void main(String[] args) {
        List<Contribution> party = List.of(
                new Contribution("ember-mage", 18_420_331L),
                new Contribution("shield-bearer", 7_905_114L),
                new Contribution("storm-ranger", 15_771_008L));

        // A game server would keep one client from startup to shutdown and dispatch this confirmed
        // reporting work off the tick thread; this scope represents that full server lifetime.
        try (CountersClient client = CountersClient.builder()
                .apiKey(System.getenv("COUNTERS_API_KEY"))
                .maxRetries(3)
                .onBatchError(RaidCompletion::handleBufferedFailure)
                .build()) {
            completeRaid(client, party);
        } catch (CountersException error) {
            handleFailure("client shutdown flush", error);
        }
    }

    private static void completeRaid(CountersClient client, List<Contribution> party) {
        // Nobody is ranked by this number, so a plain counter is the honest model. Buffering the
        // write keeps telemetry off the game-loop latency path; close() flushes it on shutdown.
        client.counter("raids-completed").add(1);

        CounterHandle damageBoard = client.counter("raid-molten-core");

        // Do not create one counter per player and sort them in the game server. Members make this
        // one sum board: counters.dev maintains each standing value, ties, ranks, and the total.
        for (Contribution contribution : party) {
            recordDamage(damageBoard, contribution);
        }

        try {
            Leaderboard top = damageBoard.leaderboard(
                    new LeaderboardParams(TOP_N, 0, "desc", null));
            MemberGetParams sameBoard = new MemberGetParams(top.epoch(), top.order());
            List<MemberSnapshot> partyStandings = party.stream()
                    .map(contribution -> damageBoard.member(contribution.playerId()).get(sameBoard))
                    .toList();
            renderResults(top, partyStandings);
        } catch (CountersException error) {
            // The raid is already complete; the UI can fall back to its local damage summary.
            handleFailure("post-raid leaderboard", error);
        }
    }

    private static void recordDamage(CounterHandle damageBoard, Contribution contribution) {
        String idempotencyKey = Idempotency.newKey();
        MemberWriteOptions options = new MemberWriteOptions(null, null, idempotencyKey);
        try {
            // Member writes are confirmed, so the results read below include this raid's damage.
            damageBoard.member(contribution.playerId()).add(contribution.damage(), options);
        } catch (CountersTransportException firstAttempt) {
            // Retry promptly, within the service's deduplication window, using the exact same member,
            // delta, and key. A fresh key here could double-count an applied-but-unacknowledged write.
            try {
                damageBoard.member(contribution.playerId()).add(contribution.damage(), options);
            } catch (CountersException retryError) {
                handleFailure("damage for " + contribution.playerId()
                        + " (idempotency " + idempotencyKey + ")", retryError);
            }
        } catch (CountersException error) {
            handleFailure("damage for " + contribution.playerId(), error);
        }
    }

    private static void handleBufferedFailure(WriteFailure failure) {
        handleFailure(
                "buffered counter " + failure.counterKey()
                        + " delta " + failure.delta()
                        + " (idempotency " + failure.idempotencyKey() + ")",
                failure.error());
    }

    private static void renderResults(Leaderboard top, List<MemberSnapshot> party) {
        // A long-running tier's cumulative damage can exceed Long.MAX_VALUE. Wire values therefore
        // remain exact decimal strings; BigInteger is the safe Java type for arithmetic or formatting.
        BigInteger seasonTotal = new BigInteger(top.total());
        System.out.println("Season damage: " + seasonTotal);
        System.out.println("Top " + TOP_N + ":");
        for (LeaderboardEntry entry : top.entries()) {
            BigInteger damage = new BigInteger(entry.value());
            // updatedAt() is already a java.time.Instant; no timestamp parsing is needed.
            System.out.printf("#%d %s — %s (updated %s)%n",
                    entry.rank(), entry.member(), damage, entry.updatedAt());
        }

        System.out.println("Your party:");
        for (MemberSnapshot member : party) {
            System.out.printf("%s — rank #%d, %s damage, percentile %s%%%n",
                    member.member(), member.rank(), new BigInteger(member.value()), member.percentile());
        }
    }

    private static void handleFailure(String operation, CountersException error) {
        if (error instanceof CountersApiException api) {
            if (api.status() == 403) {
                // Quota/permission rejection is operational; it must never undo the cleared raid.
                System.err.printf("%s skipped: quota or permission denied (%s)%n", operation, api.title());
            } else {
                System.err.printf("%s skipped: API %d (%s)%n", operation, api.status(), api.title());
            }
        } else if (error instanceof CountersTransportException transport) {
            // The SDK already retried; drop this remote report and keep the player-facing local result.
            System.err.printf("%s dropped after retries: %s%n", operation, transport.getMessage());
        } else if (error instanceof CountersValidationException validation) {
            // No request was made: this is a key/amount bug to alert on, not a player-facing failure.
            System.err.printf("invalid counters.dev input for %s: %s%n", operation, validation.getMessage());
        } else {
            System.err.printf("unexpected counters.dev failure for %s: %s%n", operation, error);
        }
    }
}
