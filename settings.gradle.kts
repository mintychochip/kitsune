pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(
    ":kitsune-api",
    ":kitsune-common",
    ":platform:bukkit",
    ":kitsune-paper"
)

rootProject.name = "kitsune"
