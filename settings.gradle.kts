pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Configure toolchain resolution for auto-download
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}

// Include Kotlin SDK as composite build
includeBuild("../kotlin-sdk") {
    dependencySubstitution {
        substitute(module("com.agentclientprotocol:acp-model")).using(project(":acp-model"))
        substitute(module("com.agentclientprotocol:acp")).using(project(":acp"))
        substitute(module("com.agentclientprotocol:acp-ktor")).using(project(":acp-ktor"))
        substitute(module("com.agentclientprotocol:acp-ktor-client")).using(project(":acp-ktor-client"))
    }
}

rootProject.name = "ACPChat"
include(":app")
