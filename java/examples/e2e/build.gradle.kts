// Standalone example app + E2E gate for the Java SDK (mirrors
// typescript/examples/e2e). Run against a live stack with the seed env exported:
//
//   COUNTERS_BASE_URL=... COUNTERS_API_KEY_A=... COUNTERS_API_KEY_B=... COUNTERS_PK_TOKEN=... \
//     ../../gradlew -p . run
plugins {
    application
}

group = "dev.counters.examples"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral() // the SDK below is substituted by includeBuild("../..") — nothing is fetched today
}

dependencies {
    implementation("dev.counters:counters-sdk:0.1.0") // substituted with the local ../.. build
}

application {
    mainClass.set("dev.counters.examples.E2e")
}
