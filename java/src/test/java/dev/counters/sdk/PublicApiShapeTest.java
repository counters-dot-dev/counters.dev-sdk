package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicApiShapeTest {

    @Test
    void seriesPointRecordComponentsUseErgonomicNamesAndInstant() {
        assertRecordShape(SeriesPoint.class,
                new String[] {"timestamp", "value"},
                new Class<?>[] {Instant.class, String.class});
    }

    @Test
    void allDateTimesAndWireNamesUseNativeErgonomicPublicShapes() {
        assertRecordShape(Counter.class,
                new String[] {"key", "value", "epoch", "memberMode", "memberSeriesEnabled",
                        "memberSeriesEnabledAt", "memberSeriesEnabledBy", "memberCount", "createdAt", "updatedAt"},
                new Class<?>[] {String.class, String.class, long.class, String.class, Boolean.class,
                        Instant.class, String.class, Long.class, Instant.class, Instant.class});
        assertRecordShape(CounterDeclarationResult.class,
                new String[] {"key", "status", "epoch", "memberMode", "memberSeriesEnabled",
                        "memberSeriesEnabledAt", "memberSeriesEnabledBy", "memberCount", "error"},
                new Class<?>[] {String.class, String.class, Long.class, String.class, Boolean.class,
                        Instant.class, String.class, Long.class, Problem.class});
        assertRecordShape(MemberSeriesConfig.class,
                new String[] {"key", "enabled", "memberCount", "maxMembersWithSeries", "mode", "enabledAt",
                        "enabledBy"},
                new Class<?>[] {String.class, boolean.class, long.class, long.class, String.class,
                        Instant.class, String.class});
        assertRecordShape(SeriesParams.class,
                new String[] {"from", "to", "bucket", "mode", "timeZone", "gapfill"},
                new Class<?>[] {Instant.class, Instant.class, String.class, String.class, String.class, Boolean.class});
        assertRecordShape(DerivedSeriesParams.class,
                new String[] {"from", "to", "bucket", "timeZone"},
                new Class<?>[] {Instant.class, Instant.class, String.class, String.class});
        assertRecordShape(MemberWriteOptions.class,
                new String[] {"metadata", "occurredAt", "idempotencyKey"},
                new Class<?>[] {String.class, Instant.class, String.class});
        assertRecordShape(SubmitOptions.class,
                new String[] {"mode", "metadata", "occurredAt", "idempotencyKey"},
                new Class<?>[] {String.class, String.class, Instant.class, String.class});
        assertRecordShape(WriteFailure.class,
                new String[] {"counterKey", "delta", "member", "idempotencyKey", "error"},
                new Class<?>[] {String.class, String.class, String.class, String.class, CountersException.class});
        assertRecordShape(Operation.class,
                new String[] {"counterKey", "operation", "amount", "idempotencyKey", "occurredAt"},
                new Class<?>[] {String.class, String.class, String.class, String.class, Instant.class});
        assertFalse(java.lang.reflect.Modifier.isPublic(Operation.class.getModifiers()),
                "Operation is an internal batch wire shape — publishing it would freeze a dead-end type");

        assertRecordShape(SeriesResponse.class,
                new String[] {"counterKey", "bucket", "mode", "timeZone", "range", "points"},
                new Class<?>[] {String.class, String.class, String.class, String.class,
                        SeriesResponse.Range.class, List.class});
        assertRecordShape(SeriesResponse.Range.class,
                new String[] {"from", "to"},
                new Class<?>[] {Instant.class, Instant.class});
        assertRecordShape(MemberSeriesResponse.class,
                new String[] {"counterKey", "member", "bucket", "mode", "timeZone", "range", "points"},
                new Class<?>[] {String.class, String.class, String.class, String.class, String.class,
                        SeriesResponse.Range.class, List.class});
        assertRecordShape(MemberGroupSeriesResponse.class,
                new String[] {"counterKey", "bucket", "mode", "timeZone", "range", "memberCount",
                        "selectedCount", "truncated", "series"},
                new Class<?>[] {String.class, String.class, String.class, String.class,
                        SeriesResponse.Range.class, long.class, long.class, boolean.class, List.class});
        assertRecordShape(DerivedSeriesPoint.class,
                new String[] {"timestamp", "value"},
                new Class<?>[] {Instant.class, String.class});
        assertRecordShape(DerivedSeriesResponse.class,
                new String[] {"key", "bucket", "timeZone", "scale", "range", "points"},
                new Class<?>[] {String.class, String.class, String.class, long.class,
                        SeriesResponse.Range.class, List.class});

        assertRecordShape(Usage.class,
                new String[] {"month", "operations", "counters", "limits"},
                new Class<?>[] {String.class, Usage.Operations.class, Usage.Counters.class, Usage.Limits.class});
        assertRecordShape(Usage.Operations.class,
                new String[] {"used", "quota", "resetsAt"},
                new Class<?>[] {long.class, Long.class, Instant.class});
        assertRecordShape(Usage.Limits.class,
                new String[] {"rateLimitRequestsPerSecond", "maxCounters", "monthlyOperationsQuota"},
                new Class<?>[] {long.class, long.class, Long.class});
        assertRecordShape(WindowLeaderboard.class,
                new String[] {"key", "mode", "window", "order", "total", "memberCount", "limit", "offset",
                        "effectiveStart", "effectiveEnd", "entries"},
                new Class<?>[] {String.class, String.class, String.class, String.class, String.class,
                        long.class, long.class, long.class, Instant.class, Instant.class, List.class});

        assertNull(new MemberWriteOptions((Instant) null).occurredAt(),
                "absent optional member occurredAt stays null");
        assertNull(new SubmitOptions("sum").occurredAt(),
                "absent optional submit occurredAt stays null");
        assertNull(new DerivedSeriesPoint(Instant.EPOCH, null).value(),
                "nullable derived DecimalValue remains data");
    }

    @Test
    void publishableBuilderReturnsTheExactReadOnlyCapabilitySurface() throws Exception {
        assertEquals(
                Set.of(
                        "counter(String):ReadOnlyCounterHandle",
                        "close():void"),
                publicDeclaredMethodSignatures(ReadOnlyCountersClient.class));
        assertEquals(
                Set.of(
                        "key():String",
                        "value():ValueResponse",
                        "series(SeriesParams):SeriesResponse",
                        "memberSeries(String,SeriesParams):MemberSeriesResponse",
                        "groupSeries(SeriesParams):MemberGroupSeriesResponse",
                        "leaderboard():Leaderboard",
                        "leaderboard(LeaderboardParams):Leaderboard",
                        "windowLeaderboard(WindowLeaderboardParams):WindowLeaderboard",
                        "member(String):ReadOnlyMemberHandle"),
                publicDeclaredMethodSignatures(ReadOnlyCounterHandle.class));
        assertEquals(
                Set.of(
                        "counterKey():String",
                        "member():String",
                        "get():MemberSnapshot",
                        "get(MemberGetParams):MemberSnapshot"),
                publicDeclaredMethodSignatures(ReadOnlyMemberHandle.class));
        assertEquals(
                Set.of(
                        "apiKey(String):PublishableBuilder",
                        "baseUrl(String):PublishableBuilder",
                        "httpClient(HttpClient):PublishableBuilder",
                        "maxRetries(int):PublishableBuilder",
                        "backoffMillis(long):PublishableBuilder",
                        "requestTimeoutMillis(long):PublishableBuilder",
                        "build():ReadOnlyCountersClient"),
                publicDeclaredMethodSignatures(CountersClient.PublishableBuilder.class));

        assertEquals(ReadOnlyCounterHandle.class,
                ReadOnlyCountersClient.class.getMethod("counter", String.class).getReturnType());
        assertEquals(ReadOnlyMemberHandle.class,
                ReadOnlyCounterHandle.class.getMethod("member", String.class).getReturnType());
        assertEquals(ReadOnlyCountersClient.class,
                CountersClient.PublishableBuilder.class.getMethod("build").getReturnType());

        try (ReadOnlyCountersClient client = CountersClient.publishableBuilder().apiKey("pk_test").build()) {
            ReadOnlyCounterHandle counter = client.counter("demo");
            ReadOnlyMemberHandle member = counter.member("alice");
            assertEquals("demo", counter.key());
            assertEquals("alice", member.member());
        }
    }

    @Test
    void publishableClientWriteDoesNotCompile(@TempDir Path tempDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests must run on a JDK so the compile-fail public API check is available");

        Path source = tempDir.resolve("PublishableWriteMustFail.java");
        Files.writeString(source, """
                import dev.counters.sdk.CountersClient;

                final class PublishableWriteMustFail {
                    void write() {
                        CountersClient.publishableBuilder()
                                .apiKey("pk_test")
                                .build()
                                .counter("demo")
                                .add(1);
                    }
                }
                """);
        Path output = Files.createDirectory(tempDir.resolve("classes"));
        String sdkClasses = Path.of(ReadOnlyCountersClient.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI())
                .toString();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        boolean compiled;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(source.toFile());
            compiled = Boolean.TRUE.equals(compiler.getTask(
                            null,
                            files,
                            diagnostics,
                            java.util.List.of("-classpath", sdkClasses, "-d", output.toString()),
                            null,
                            units)
                    .call());
        }

        String messages = diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        assertFalse(compiled, "publishable client unexpectedly exposed a write method");
        assertTrue(messages.contains("add"), "expected the missing add method in compiler diagnostics:\n" + messages);
    }

    @Test
    void batchErrorCallbackCarriesTheFailedWriteIdentity() throws Exception {
        Method method = CountersClient.Builder.class.getMethod("onBatchError", Consumer.class);
        assertEquals("java.util.function.Consumer<dev.counters.sdk.WriteFailure>",
                method.getGenericParameterTypes()[0].getTypeName());
    }

    private static Set<String> publicDeclaredMethodSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
                .map(method -> method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(","))
                        + "):" + method.getReturnType().getSimpleName())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void assertRecordShape(Class<?> type, String[] names, Class<?>[] types) {
        RecordComponent[] components = type.getRecordComponents();
        assertArrayEquals(names,
                Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new),
                type.getSimpleName() + " component names");
        assertArrayEquals(types,
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new),
                type.getSimpleName() + " component types");
    }
}
