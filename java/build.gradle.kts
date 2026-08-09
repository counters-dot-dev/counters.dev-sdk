plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

group = "dev.counters"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// One-line summary that survives `gradle -q`.
//
// This used to be `Test.afterSuite`. Gradle 9 deprecates that callback and Gradle 10 removes it —
// it cannot be carried across a configuration-cache boundary — so the totals are read back out of
// the JUnit XML the test task has just written instead. Capturing only the output directory as a
// Provider keeps the task action free of live Project state, which is what the removal was about.
//
// It is a finalizer rather than a `doLast` so that the line still prints when the suite fails,
// which is the case you most want it in. `afterSuite` behaved the same way.
//
// Registered with `tasks.register(...)`, not the `val x by tasks.registering` delegate: that
// delegate syntax is itself deprecated and scheduled for removal in Gradle 10, so using it here
// would have swapped one Gradle 10 removal for another.
val testSummary = tasks.register("testSummary") {
    description = "Prints a one-line pass/fail summary of the last test run."
    // Derived from `layout`, not from `tasks.test.flatMap { it.reports.junitXml.outputLocation }`.
    // The latter reads better but serialises as a FlatMapProvider holding a reference to the Test
    // task, which the configuration cache cannot write — so it fails the very check this rewrite
    // exists to satisfy. This is the default JUnit XML location and the build does not move it.
    val resultsDir = layout.buildDirectory.dir("test-results/test")
    doLast {
        val dir = resultsDir.get().asFile
        val xml = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty()
        if (xml.isEmpty()) {
            println("Result: NO-RESULTS (no JUnit XML under $dir)")
            return@doLast
        }
        // Each file opens with a single <testsuite ...> element carrying the per-class totals.
        val header = Regex("""<testsuite\b[^>]*>""")
        fun count(head: String, name: String) =
            Regex("""\b$name="(\d+)"""").find(head)?.groupValues?.get(1)?.toInt() ?: 0

        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0
        for (file in xml) {
            val head = header.find(file.readText()) ?: continue
            tests += count(head.value, "tests")
            failures += count(head.value, "failures")
            errors += count(head.value, "errors")
            skipped += count(head.value, "skipped")
        }
        // An <error> is a test that blew up rather than asserted false; both are failures here,
        // which is how afterSuite's failedTestCount counted them too.
        val failed = failures + errors
        val outcome = if (failed > 0) "FAILURE" else "SUCCESS"
        println(
            "Result: $outcome " +
                "($tests tests, ${tests - failed - skipped} passed, $failed failed, $skipped skipped)"
        )
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport, testSummary)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// --- Publishing (Maven Central via Central Portal). ---
// Real publish is tag-gated (`java-v*`) in .github/workflows/sdk-maven-publish.yml and is currently
// ON HOLD. `publishToMavenLocal` is the dry-run path (no credentials, no signing). Signing runs only
// when a key is provided, so local/dry-run builds don't need GPG.
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "counters-sdk"
            from(components["java"])
            pom {
                name.set("Counters Java SDK")
                description.set("Official Java SDK for counters.dev — arbitrary-precision counters.")
                url.set("https://counters.dev")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("counters-dot-dev")
                        name.set("counters.dev")
                    }
                }
                scm {
                    url.set("https://github.com/counters-dot-dev/counters.dev-sdk")
                    connection.set("scm:git:https://github.com/counters-dot-dev/counters.dev-sdk.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "centralPortal"
            url = uri("https://central.sonatype.com/api/v1/publisher/upload")
            credentials {
                username = System.getenv("MAVEN_CENTRAL_USERNAME")
                password = System.getenv("MAVEN_CENTRAL_PASSWORD")
            }
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}
