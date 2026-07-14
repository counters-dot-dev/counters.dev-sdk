// Standalone narrative example. The published SDK coordinate is substituted with
// the checked-out Java build by settings.gradle.kts.
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
    mavenCentral()
}

dependencies {
    implementation("dev.counters:counters-sdk:0.1.0")
}

application {
    mainClass.set("dev.counters.examples.RaidCompletion")
}
