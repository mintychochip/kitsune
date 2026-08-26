plugins {
    `java-base`
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

tasks.runServer {
    minecraftVersion(rootProject.providers.gradleProperty("minecraftVersion").get())
    pluginJars.from(project(":kitsune-paper").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("-Dcom.mojang.eula.agree=true")
}
