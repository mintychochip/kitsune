plugins {
    java
    id("com.gradleup.shadow") version "9.4.3"
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":common"))
    implementation(project(":api"))
    implementation(project(":platform:bukkit"))
    compileOnly(libs.spigot.api)
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
