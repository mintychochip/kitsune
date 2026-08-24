plugins {
    java
    id("com.gradleup.shadow") version "9.4.3"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":kitsune-common"))
    implementation(project(":kitsune-api"))
    implementation(project(":platform:bukkit"))
    compileOnly(libs.paper.api)
    testCompileOnly(libs.paper.api)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion(rootProject.providers.gradleProperty("minecraftVersion").get())
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("-Dcom.mojang.eula.agree=true")
}

tasks.test {
    useJUnitPlatform()
}
