plugins {
    java
    id("com.gradleup.shadow") version "9.4.3"
}

repositories {
    maven("https://repo.codemc.io/repository/maven-public/")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":kitsune-common"))
    implementation(project(":kitsune-api"))
    compileOnly(libs.spigot.api)
    compileOnly(libs.adventure.api)
    compileOnly("org.popcraft:bolt-bukkit:1.1.52")
    testCompileOnly(libs.spigot.api)
    testCompileOnly(libs.adventure.api)
    testCompileOnly("org.popcraft:bolt-bukkit:1.1.52")
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.adventure.text.serializer.plain)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.spigot.api)
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

tasks.test {
    useJUnitPlatform()
}
