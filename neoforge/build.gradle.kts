plugins {
    java
    id("net.neoforged.moddev") version "2.0.143"
}

neoForge {
    version = libs.versions.neoforge.get()
    mods {
        create("kitsune") {
            sourceSet(sourceSets.main.get())
        }
    }
}

val neoForgeMergedJar = layout.buildDirectory.file(
    "moddev/artifacts/neoforge-${libs.versions.neoforge.get()}-merged.jar"
)

dependencies {
    implementation(project(":common"))
    implementation(project(":api"))
    testRuntimeOnly(files(neoForgeMergedJar))
    testRuntimeOnly("com.mojang:brigadier:1.3.10")
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.16")
    testRuntimeOnly("net.neoforged:neoforge:${rootProject.libs.versions.neoforge.get()}")
    testImplementation(platform("org.junit:junit-bom:${rootProject.libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
