plugins {
    java
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":common"))
    implementation(project(":api"))
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
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    jvmArgs("-Dcom.mojang.eula.agree=true")
}

tasks.test {
    useJUnitPlatform()
}
