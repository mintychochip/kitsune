plugins {
    id("fabric-loom") version "1.9.2"
    java
}
java {
    withSourcesJar()
    withJavadocJar()
}

tasks.jar {
    dependsOn(project(":api").tasks.named("classes"), project(":common").tasks.named("classes"))
    from(project(":api").sourceSets.main.get().output)
    from(project(":common").sourceSets.main.get().output)
    project(":common").configurations.getByName("runtimeClasspath")
        .resolve()
        .filter { it.name.startsWith("sqlite-jdbc-") }
        .forEach { from(zipTree(it)) }
}

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.4")
    mappings("net.fabricmc:yarn:1.21.4+build.8:v2")
    modImplementation("net.fabricmc:fabric-loader:0.16.10")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.119.4+1.21.4")
    implementation(project(":common"))
    implementation(project(":api"))
    testImplementation(platform("org.junit:junit-bom:${rootProject.libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
