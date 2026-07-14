package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
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
        RecordComponent[] components = SeriesPoint.class.getRecordComponents();
        assertArrayEquals(new String[] {"timestamp", "value"},
                Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new));
        assertArrayEquals(new Class<?>[] {Instant.class, String.class},
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
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
    void batchErrorCallbackUsesTheSdkExceptionBaseType() throws Exception {
        Method method = CountersClient.Builder.class.getMethod("onBatchError", Consumer.class);
        assertEquals("java.util.function.Consumer<dev.counters.sdk.CountersException>",
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
}
