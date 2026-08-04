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
    ":api",
    ":common",
    ":platform:bukkit",
    ":paper",
    ":spigot",
    ":fabric",
    ":forge",
    ":neoforge"
)

rootProject.name = "kitsune"
