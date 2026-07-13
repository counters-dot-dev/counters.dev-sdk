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

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // One-line summary that survives `gradle -q`.
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null) {
            println(
                "Result: ${result.resultType} " +
                    "(${result.testCount} tests, ${result.successfulTestCount} passed, " +
                    "${result.failedTestCount} failed, ${result.skippedTestCount} skipped)"
            )
        }
    }))
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
                description.set("Official Java SDK for counters.dev — multi-tenant arbitrary-precision counters.")
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
