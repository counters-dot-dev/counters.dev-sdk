// The example app is a standalone build that composes the SDK: the binary dependency
// `dev.counters:counters-sdk` declared in build.gradle.kts is substituted with the local
// ../.. project, so `run` always exercises the checked-out SDK sources.
rootProject.name = "counters-sdk-e2e"

includeBuild("../..")
